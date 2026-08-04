// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 etzhayyim Japan株式会社 / etzhayyim. All rights reserved.
// Licensed under the Apache License, Version 2.0 — see LICENSE at repo root.

// etzhayyim-project-open-gas — natural-gas distribution operations + network design
//
// 9 XRPC under com.etzhayyim.apps.openGas.*:
//   defineRegulator      (proc)   regulator / city-gate node
//   definePipeSegment    (proc)   pipe segment + downstream service points + MAOP
//   getNode              (query)  node detail + downstream segments
//   listSegments         (query)  segments by regulator / status
//   recordReading        (proc)   meter m³ reading
//   reportLeak           (proc)   leak with class DMN
//   listLeaks            (query)  leaks by segment / since / minClass
//   recordPressureLog    (proc)   inlet/outlet pressure sample (alarms on > MAOP)
//   listPressureLogs     (query)  pressure logs by segment / since
//
// Storage: D1. Topology = nodes (regulator / service_point) + segments.
// Leak grade via openGas.leakClass DMN (mirrored). Class 1 → emergency post.
// Pressure > MAOP → audit post.

import AV1 from "../../dodaf/AV-1.json";
import OV1 from "../../dodaf/OV-1.json";
import OV5b from "../../dodaf/OV-5b.json";
import OV6a from "../../dodaf/OV-6a.json";
import CV2 from "../../dodaf/CV-2.json";
import SV1 from "../../dodaf/SV-1.json";
import defineSegmentForm from "../../forms/definePipeSegment.form.json";
import reportLeakForm from "../../forms/reportLeak.form.json";
import { bootstrapDodaf } from "./dodaf-bootstrap";

const DODAF_VIEWS: Record<string, any> = {
  "open-gas.AV-1": AV1, "open-gas.OV-1": OV1, "open-gas.OV-5b": OV5b,
  "open-gas.OV-6a": OV6a, "open-gas.CV-2": CV2, "open-gas.SV-1": SV1,
};
const FORMS: Record<string, any> = {
  "openGas.definePipeSegment.v1": defineSegmentForm,
  "openGas.reportLeak.v1": reportLeakForm,
};

export interface Env {
  GAS_DB: D1Database;
  PDS?: Fetcher;
  AUTH_SERVICE?: Fetcher;
  APP_HANDLE: string;
  PRIMARY_DID: string;
}

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS nodes (
    node_did TEXT PRIMARY KEY,
    node_type TEXT NOT NULL CHECK (node_type IN ('regulator','service_point')),
    node_code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    operator_did TEXT NOT NULL,
    parent_segment_did TEXT,
    outlet_design_kpa REAL,
    defined_at TEXT NOT NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_nodes_type ON nodes(node_type)`,
  `CREATE INDEX IF NOT EXISTS idx_nodes_parent ON nodes(parent_segment_did)`,
  `CREATE TABLE IF NOT EXISTS segments (
    segment_did TEXT PRIMARY KEY,
    segment_code TEXT NOT NULL UNIQUE,
    regulator_did TEXT NOT NULL,
    diameter_mm INTEGER NOT NULL,
    material TEXT NOT NULL,
    length_m REAL NOT NULL,
    maop_kpa REAL NOT NULL,
    status TEXT NOT NULL DEFAULT 'in-service',
    defined_at TEXT NOT NULL,
    FOREIGN KEY (regulator_did) REFERENCES nodes(node_did)
  )`,
  `CREATE INDEX IF NOT EXISTS idx_segments_reg ON segments(regulator_did)`,
  `CREATE TABLE IF NOT EXISTS meter_readings (
    reading_id TEXT PRIMARY KEY,
    service_point_did TEXT NOT NULL,
    segment_did TEXT NOT NULL,
    read_at TEXT NOT NULL,
    cubic_m REAL NOT NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_readings_sp_time ON meter_readings(service_point_did, read_at DESC)`,
  `CREATE TABLE IF NOT EXISTS leaks (
    leak_did TEXT PRIMARY KEY,
    segment_did TEXT NOT NULL,
    detected_at TEXT NOT NULL,
    restored_at TEXT,
    leak_class INTEGER NOT NULL CHECK (leak_class IN (1,2,3)),
    response_sla TEXT NOT NULL,
    indoor_or_confined INTEGER NOT NULL,
    requires_evacuation INTEGER NOT NULL,
    hazard_to_persons INTEGER NOT NULL,
    hazard_to_property INTEGER NOT NULL,
    est_ppm_methane REAL,
    location_description TEXT,
    description TEXT,
    require_emergency_notice INTEGER NOT NULL,
    reported_at TEXT NOT NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_leaks_seg_time ON leaks(segment_did, detected_at DESC)`,
  `CREATE TABLE IF NOT EXISTS pressure_logs (
    log_id TEXT PRIMARY KEY,
    segment_did TEXT NOT NULL,
    sampled_at TEXT NOT NULL,
    inlet_kpa REAL,
    outlet_kpa REAL,
    over_maop INTEGER NOT NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_pressure_seg_time ON pressure_logs(segment_did, sampled_at DESC)`,
];

let schemaReady = false;
async function ensureSchema(db: D1Database) {
  if (schemaReady) return;
  for (const s of SCHEMA) await db.exec(s.replace(/\s+/g, " "));
  schemaReady = true;
}

function nanoid(len = 12): string {
  const a = "abcdefghijklmnopqrstuvwxyz0123456789";
  const b = crypto.getRandomValues(new Uint8Array(len));
  let o = ""; for (let i = 0; i < len; i++) o += a[b[i] % a.length]; return o;
}
const now = () => new Date().toISOString();

type XrpcError = "InvalidRequest" | "NodeNotFound" | "SegmentNotFound" | "Conflict" | "Unauthorized" | "InternalError";
const json = (b: unknown, s = 200) => new Response(JSON.stringify(b), { status: s, headers: { "content-type": "application/json" } });
const err = (e: XrpcError, m: string, s = 400) => json({ error: e, message: m }, s);

function classifyLeakDmn(input: {
  indoorOrConfined: boolean; requiresEvacuation: boolean;
  hazardToPersons: boolean; hazardToProperty: boolean;
}): { leakClass: 1 | 2 | 3; responseSla: string; requireEmergencyNotice: boolean } {
  if (input.indoorOrConfined || input.requiresEvacuation || input.hazardToPersons)
    return { leakClass: 1, responseSla: "immediate", requireEmergencyNotice: true };
  if (input.hazardToProperty)
    return { leakClass: 2, responseSla: "P30d", requireEmergencyNotice: false };
  return { leakClass: 3, responseSla: "P15m", requireEmergencyNotice: false };
}

async function defineRegulator(env: Env, input: any): Promise<Response> {
  const { nodeCode, name, operatorDid, outletDesignKpa } = input ?? {};
  if (typeof nodeCode !== "string" || !/^[A-Z0-9-]{2,32}$/.test(nodeCode))
    return err("InvalidRequest", "nodeCode required");
  if (typeof name !== "string" || !name.length) return err("InvalidRequest", "name required");
  if (typeof operatorDid !== "string" || !operatorDid.startsWith("did:"))
    return err("InvalidRequest", "operatorDid must be a DID");
  if (!Number.isFinite(outletDesignKpa) || outletDesignKpa <= 0)
    return err("InvalidRequest", "outletDesignKpa > 0 required");
  const id = nanoid(10);
  const nodeDid = `did:web:${env.APP_HANDLE}:node:${id}`;
  const definedAt = now();
  await env.GAS_DB.prepare(
    `INSERT INTO nodes (node_did, node_type, node_code, name, operator_did, parent_segment_did, outlet_design_kpa, defined_at)
     VALUES (?, 'regulator', ?, ?, ?, NULL, ?, ?)`
  ).bind(nodeDid, nodeCode, name, operatorDid, outletDesignKpa, definedAt).run();
  return json({ nodeDid, nodeType: "regulator", nodeCode, name, operatorDid, outletDesignKpa, definedAt });
}

async function definePipeSegment(env: Env, input: any): Promise<Response> {
  const { regulatorDid, segmentCode, diameterMm, material, lengthM, maopKpa, servicePoints } = input ?? {};
  if (typeof regulatorDid !== "string") return err("InvalidRequest", "regulatorDid required");
  if (typeof segmentCode !== "string" || !/^[A-Z0-9-]{2,16}$/.test(segmentCode))
    return err("InvalidRequest", "segmentCode required");
  if (!Number.isInteger(diameterMm) || diameterMm < 25 || diameterMm > 1500)
    return err("InvalidRequest", "diameterMm must be 25..1500");
  if (typeof material !== "string") return err("InvalidRequest", "material required");
  if (!Number.isFinite(lengthM) || lengthM <= 0)
    return err("InvalidRequest", "lengthM > 0 required");
  if (!Number.isFinite(maopKpa) || maopKpa <= 0)
    return err("InvalidRequest", "maopKpa > 0 required");
  if (!Array.isArray(servicePoints) || servicePoints.length < 1)
    return err("InvalidRequest", "servicePoints[] requires ≥ 1 entry");
  const r = await env.GAS_DB.prepare(`SELECT * FROM nodes WHERE node_did = ?`).bind(regulatorDid).first<any>();
  if (!r) return err("NodeNotFound", "regulator not found", 404);
  if (r.node_type !== "regulator") return err("InvalidRequest", "origin must be regulator");
  if (maopKpa > r.outlet_design_kpa)
    return err("InvalidRequest", "MAOP exceeds regulator outlet design pressure");

  const sid = nanoid(10);
  const segmentDid = `did:web:${env.APP_HANDLE}:segment:${sid}`;
  const definedAt = now();
  const stmts: D1PreparedStatement[] = [
    env.GAS_DB.prepare(
      `INSERT INTO segments (segment_did, segment_code, regulator_did, diameter_mm, material, length_m, maop_kpa, status, defined_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, 'in-service', ?)`
    ).bind(segmentDid, segmentCode, regulatorDid, diameterMm, material, lengthM, maopKpa, definedAt),
  ];
  const servicePointDids: string[] = [];
  for (const sp of servicePoints) {
    if (typeof sp?.code !== "string" || typeof sp?.name !== "string")
      return err("InvalidRequest", "service point requires {code,name}");
    const pid = nanoid(10);
    const spDid = `did:web:${env.APP_HANDLE}:node:${pid}`;
    servicePointDids.push(spDid);
    stmts.push(env.GAS_DB.prepare(
      `INSERT INTO nodes (node_did, node_type, node_code, name, operator_did, parent_segment_did, outlet_design_kpa, defined_at)
       VALUES (?, 'service_point', ?, ?, ?, ?, NULL, ?)`
    ).bind(spDid, sp.code, sp.name, r.operator_did, segmentDid, definedAt));
  }
  await env.GAS_DB.batch(stmts);
  return json({ segmentDid, segmentCode, regulatorDid, diameterMm, material, lengthM, maopKpa, servicePointDids, definedAt });
}

async function getNode(env: Env, params: URLSearchParams): Promise<Response> {
  const nodeDid = params.get("nodeDid");
  if (!nodeDid) return err("InvalidRequest", "nodeDid required");
  const n = await env.GAS_DB.prepare(`SELECT * FROM nodes WHERE node_did = ?`).bind(nodeDid).first<any>();
  if (!n) return err("NodeNotFound", "no such node", 404);
  let downstream: any[] = [];
  if (n.node_type === "regulator") {
    const s = await env.GAS_DB.prepare(`SELECT * FROM segments WHERE regulator_did = ?`).bind(nodeDid).all<any>();
    downstream = (s.results ?? []).map((r) => ({
      segmentDid: r.segment_did, segmentCode: r.segment_code, diameterMm: r.diameter_mm,
      material: r.material, lengthM: r.length_m, maopKpa: r.maop_kpa, status: r.status,
    }));
  }
  return json({
    nodeDid: n.node_did, nodeType: n.node_type, nodeCode: n.node_code, name: n.name,
    operatorDid: n.operator_did, parentSegmentDid: n.parent_segment_did ?? undefined,
    outletDesignKpa: n.outlet_design_kpa ?? undefined, definedAt: n.defined_at,
    downstreamSegments: downstream,
  });
}

async function listSegments(env: Env, params: URLSearchParams): Promise<Response> {
  const reg = params.get("regulatorDid");
  const status = params.get("status");
  const limit = Math.min(500, Math.max(1, Number(params.get("limit") ?? 100)));
  const offset = Math.max(0, Number(params.get("offset") ?? 0));
  const clauses: string[] = []; const binds: any[] = [];
  if (reg) { clauses.push(`regulator_did = ?`); binds.push(reg); }
  if (status) { clauses.push(`status = ?`); binds.push(status); }
  const where = clauses.length ? `WHERE ${clauses.join(" AND ")}` : "";
  const total = await env.GAS_DB.prepare(`SELECT COUNT(*) AS c FROM segments ${where}`)
    .bind(...binds).first<{ c: number }>();
  const rows = await env.GAS_DB.prepare(
    `SELECT * FROM segments ${where} ORDER BY defined_at DESC LIMIT ? OFFSET ?`
  ).bind(...binds, limit, offset).all<any>();
  return json({
    segments: (rows.results ?? []).map((r) => ({
      segmentDid: r.segment_did, segmentCode: r.segment_code, regulatorDid: r.regulator_did,
      diameterMm: r.diameter_mm, material: r.material, lengthM: r.length_m, maopKpa: r.maop_kpa,
      status: r.status, definedAt: r.defined_at,
    })),
    total: Number(total?.c ?? 0), offset, limit,
  });
}

async function recordReading(env: Env, input: any): Promise<Response> {
  const { servicePointDid, readAt, cubicM } = input ?? {};
  if (typeof servicePointDid !== "string") return err("InvalidRequest", "servicePointDid required");
  if (typeof readAt !== "string") return err("InvalidRequest", "readAt required");
  if (!Number.isFinite(cubicM) || cubicM < 0) return err("InvalidRequest", "cubicM ≥ 0");
  const sp = await env.GAS_DB.prepare(
    `SELECT * FROM nodes WHERE node_did = ? AND node_type = 'service_point'`
  ).bind(servicePointDid).first<any>();
  if (!sp) return err("NodeNotFound", "service point not found", 404);
  if (!sp.parent_segment_did) return err("Conflict", "service point not connected", 409);
  const last = await env.GAS_DB.prepare(
    `SELECT cubic_m FROM meter_readings WHERE service_point_did = ? ORDER BY read_at DESC LIMIT 1`
  ).bind(servicePointDid).first<{ cubic_m: number }>();
  if (last && cubicM < last.cubic_m) return err("Conflict", "reading not monotonic", 409);
  const id = `r_${nanoid(14)}`;
  await env.GAS_DB.prepare(
    `INSERT INTO meter_readings (reading_id, service_point_did, segment_did, read_at, cubic_m) VALUES (?, ?, ?, ?, ?)`
  ).bind(id, servicePointDid, sp.parent_segment_did, readAt, cubicM).run();
  return json({ readingId: id, servicePointDid, segmentDid: sp.parent_segment_did, readAt, cubicM });
}

async function reportLeak(env: Env, input: any): Promise<Response> {
  const { segmentDid, detectedAt, restoredAt, indoorOrConfined, requiresEvacuation,
          hazardToPersons, hazardToProperty, estPpmMethane, locationDescription, description } = input ?? {};
  if (typeof segmentDid !== "string") return err("InvalidRequest", "segmentDid required");
  if (typeof detectedAt !== "string") return err("InvalidRequest", "detectedAt required");
  const seg = await env.GAS_DB.prepare(`SELECT segment_did FROM segments WHERE segment_did = ?`).bind(segmentDid).first<any>();
  if (!seg) return err("SegmentNotFound", "no such segment", 404);
  const { leakClass, responseSla, requireEmergencyNotice } = classifyLeakDmn({
    indoorOrConfined: !!indoorOrConfined, requiresEvacuation: !!requiresEvacuation,
    hazardToPersons: !!hazardToPersons, hazardToProperty: !!hazardToProperty,
  });
  const id = nanoid(12);
  const leakDid = `did:web:${env.APP_HANDLE}:leak:${id}`;
  const reportedAt = now();
  await env.GAS_DB.prepare(
    `INSERT INTO leaks (leak_did, segment_did, detected_at, restored_at, leak_class, response_sla,
                        indoor_or_confined, requires_evacuation, hazard_to_persons, hazard_to_property,
                        est_ppm_methane, location_description, description, require_emergency_notice, reported_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(leakDid, segmentDid, detectedAt, restoredAt ?? null, leakClass, responseSla,
         indoorOrConfined ? 1 : 0, requiresEvacuation ? 1 : 0,
         hazardToPersons ? 1 : 0, hazardToProperty ? 1 : 0,
         Number.isFinite(estPpmMethane) ? estPpmMethane : null,
         locationDescription ?? null, description ?? null,
         requireEmergencyNotice ? 1 : 0, reportedAt).run();
  if (requireEmergencyNotice && env.PDS) {
    try {
      await env.PDS.fetch(`https://atproto.etzhayyim.com/xrpc/com.atproto.repo.createRecord`, {
        method: "POST", headers: { "content-type": "application/json" },
        body: JSON.stringify({
          repo: env.PRIMARY_DID, collection: "app.bsky.feed.post",
          record: { $type: "app.bsky.feed.post",
            text: `[CLASS-1 GAS LEAK] ${segmentDid} (immediate response). evac=${!!requiresEvacuation}`,
            createdAt: reportedAt },
        }),
      });
    } catch {}
  }
  return json({ leakDid, segmentDid, leakClass, responseSla, requireEmergencyNotice, reportedAt });
}

async function listLeaks(env: Env, params: URLSearchParams): Promise<Response> {
  const segmentDid = params.get("segmentDid");
  const since = params.get("since");
  const minClass = params.get("minClass");
  const limit = Math.min(500, Math.max(1, Number(params.get("limit") ?? 100)));
  const offset = Math.max(0, Number(params.get("offset") ?? 0));
  const clauses: string[] = []; const binds: any[] = [];
  if (segmentDid) { clauses.push(`segment_did = ?`); binds.push(segmentDid); }
  if (since)      { clauses.push(`detected_at >= ?`); binds.push(since); }
  const where = clauses.length ? `WHERE ${clauses.join(" AND ")}` : "";
  const total = await env.GAS_DB.prepare(`SELECT COUNT(*) AS c FROM leaks ${where}`)
    .bind(...binds).first<{ c: number }>();
  const rows = await env.GAS_DB.prepare(
    `SELECT * FROM leaks ${where} ORDER BY detected_at DESC LIMIT ? OFFSET ?`
  ).bind(...binds, limit, offset).all<any>();
  let leaks = (rows.results ?? []).map((r) => ({
    leakDid: r.leak_did, segmentDid: r.segment_did, detectedAt: r.detected_at,
    restoredAt: r.restored_at ?? undefined, leakClass: r.leak_class, responseSla: r.response_sla,
    indoorOrConfined: !!r.indoor_or_confined, requiresEvacuation: !!r.requires_evacuation,
    hazardToPersons: !!r.hazard_to_persons, hazardToProperty: !!r.hazard_to_property,
    estPpmMethane: r.est_ppm_methane ?? undefined,
    locationDescription: r.location_description ?? undefined,
    description: r.description ?? undefined,
    requireEmergencyNotice: !!r.require_emergency_notice, reportedAt: r.reported_at,
  }));
  if (minClass) {
    const m = Number(minClass);
    if (Number.isFinite(m)) leaks = leaks.filter((l) => l.leakClass <= m); // class 1 most severe
  }
  return json({ leaks, total: Number(total?.c ?? 0), offset, limit });
}

async function recordPressureLog(env: Env, input: any): Promise<Response> {
  const { segmentDid, sampledAt, inletKpa, outletKpa } = input ?? {};
  if (typeof segmentDid !== "string") return err("InvalidRequest", "segmentDid required");
  if (typeof sampledAt !== "string") return err("InvalidRequest", "sampledAt required");
  if (inletKpa != null && !Number.isFinite(inletKpa)) return err("InvalidRequest", "inletKpa numeric");
  if (outletKpa != null && !Number.isFinite(outletKpa)) return err("InvalidRequest", "outletKpa numeric");
  const seg = await env.GAS_DB.prepare(`SELECT * FROM segments WHERE segment_did = ?`).bind(segmentDid).first<any>();
  if (!seg) return err("SegmentNotFound", "no such segment", 404);
  const overMaop = (Number.isFinite(inletKpa)  && inletKpa  > seg.maop_kpa)
                || (Number.isFinite(outletKpa) && outletKpa > seg.maop_kpa);
  const id = `pl_${nanoid(14)}`;
  await env.GAS_DB.prepare(
    `INSERT INTO pressure_logs (log_id, segment_did, sampled_at, inlet_kpa, outlet_kpa, over_maop)
     VALUES (?, ?, ?, ?, ?, ?)`
  ).bind(id, segmentDid, sampledAt,
         Number.isFinite(inletKpa) ? inletKpa : null,
         Number.isFinite(outletKpa) ? outletKpa : null,
         overMaop ? 1 : 0).run();
  if (overMaop && env.PDS) {
    try {
      await env.PDS.fetch(`https://atproto.etzhayyim.com/xrpc/com.atproto.repo.createRecord`, {
        method: "POST", headers: { "content-type": "application/json" },
        body: JSON.stringify({
          repo: env.PRIMARY_DID, collection: "app.bsky.feed.post",
          record: { $type: "app.bsky.feed.post",
            text: `[MAOP EXCURSION] ${segmentDid} inlet=${inletKpa ?? "-"}kPa outlet=${outletKpa ?? "-"}kPa MAOP=${seg.maop_kpa}kPa`,
            createdAt: now() },
        }),
      });
    } catch {}
  }
  return json({ logId: id, segmentDid, sampledAt, inletKpa: inletKpa ?? null, outletKpa: outletKpa ?? null, overMaop });
}

async function listPressureLogs(env: Env, params: URLSearchParams): Promise<Response> {
  const segmentDid = params.get("segmentDid");
  if (!segmentDid) return err("InvalidRequest", "segmentDid required");
  const since = params.get("since");
  const limit = Math.min(1000, Math.max(1, Number(params.get("limit") ?? 200)));
  const offset = Math.max(0, Number(params.get("offset") ?? 0));
  const clauses = [`segment_did = ?`]; const binds: any[] = [segmentDid];
  if (since) { clauses.push(`sampled_at >= ?`); binds.push(since); }
  const where = `WHERE ${clauses.join(" AND ")}`;
  const total = await env.GAS_DB.prepare(`SELECT COUNT(*) AS c FROM pressure_logs ${where}`)
    .bind(...binds).first<{ c: number }>();
  const rows = await env.GAS_DB.prepare(
    `SELECT * FROM pressure_logs ${where} ORDER BY sampled_at DESC LIMIT ? OFFSET ?`
  ).bind(...binds, limit, offset).all<any>();
  return json({
    logs: (rows.results ?? []).map((r) => ({
      logId: r.log_id, segmentDid: r.segment_did, sampledAt: r.sampled_at,
      inletKpa: r.inlet_kpa ?? undefined, outletKpa: r.outlet_kpa ?? undefined,
      overMaop: !!r.over_maop,
    })),
    total: Number(total?.c ?? 0), offset, limit,
  });
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    try {
      await ensureSchema(env.GAS_DB);
      const url = new URL(req.url);
      if (url.pathname === "/health" || url.pathname === "/_worker/health")
        return json({ ok: true, did: env.PRIMARY_DID, ts: now() });
      if (url.pathname === "/_app/meta") {
        if (env.PDS) { try { await bootstrapDodaf(env as any); } catch {} }
        return json({
          did: env.PRIMARY_DID, handle: env.APP_HANDLE,
          xrpc: [
            "com.etzhayyim.apps.openGas.defineRegulator",
            "com.etzhayyim.apps.openGas.definePipeSegment",
            "com.etzhayyim.apps.openGas.getNode",
            "com.etzhayyim.apps.openGas.listSegments",
            "com.etzhayyim.apps.openGas.recordReading",
            "com.etzhayyim.apps.openGas.reportLeak",
            "com.etzhayyim.apps.openGas.listLeaks",
            "com.etzhayyim.apps.openGas.recordPressureLog",
            "com.etzhayyim.apps.openGas.listPressureLogs",
          ],
          dodaf: Object.keys(DODAF_VIEWS), forms: Object.keys(FORMS),
          bpmn: ["definePipeSegment", "reportGasLeak"], dmn: ["openGas.leakClass"],
        });
      }
      if (url.pathname === "/dodaf")
        return json({ views: Object.entries(DODAF_VIEWS).map(([id, v]: any) => ({
          viewId: id, viewType: v.viewType, title: v.title, version: v.version })) });
      if (url.pathname.startsWith("/dodaf/")) {
        const id = decodeURIComponent(url.pathname.slice("/dodaf/".length));
        const v = DODAF_VIEWS[id];
        return v ? json(v) : err("InvalidRequest", `no such view: ${id}`, 404);
      }
      if (url.pathname === "/forms")
        return json({ forms: Object.values(FORMS).map((f: any) => ({ formKey: f.formKey, name: f.name, version: f.version })) });
      if (url.pathname.startsWith("/forms/")) {
        const k = decodeURIComponent(url.pathname.slice("/forms/".length));
        const f = FORMS[k];
        return f ? json(f) : err("InvalidRequest", `no such form: ${k}`, 404);
      }
      if (!url.pathname.startsWith("/xrpc/"))
        return err("InvalidRequest", "only /xrpc/* is served", 404);
      const nsid = url.pathname.slice("/xrpc/".length);
      if (req.method === "GET") {
        switch (nsid) {
          case "com.etzhayyim.apps.openGas.getNode":          return await getNode(env, url.searchParams);
          case "com.etzhayyim.apps.openGas.listSegments":     return await listSegments(env, url.searchParams);
          case "com.etzhayyim.apps.openGas.listLeaks":        return await listLeaks(env, url.searchParams);
          case "com.etzhayyim.apps.openGas.listPressureLogs": return await listPressureLogs(env, url.searchParams);
          default: return err("InvalidRequest", `unknown query NSID: ${nsid}`, 404);
        }
      }
      if (req.method === "POST") {
        const body = await req.json().catch(() => ({}));
        switch (nsid) {
          case "com.etzhayyim.apps.openGas.defineRegulator":    return await defineRegulator(env, body);
          case "com.etzhayyim.apps.openGas.definePipeSegment":  return await definePipeSegment(env, body);
          case "com.etzhayyim.apps.openGas.recordReading":      return await recordReading(env, body);
          case "com.etzhayyim.apps.openGas.reportLeak":         return await reportLeak(env, body);
          case "com.etzhayyim.apps.openGas.recordPressureLog":  return await recordPressureLog(env, body);
          default: return err("InvalidRequest", `unknown procedure NSID: ${nsid}`, 404);
        }
      }
      return err("InvalidRequest", "method not allowed", 405);
    } catch (e: any) {
      return err("InternalError", e?.message ?? String(e), 500);
    }
  },
};
