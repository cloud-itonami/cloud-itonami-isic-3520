/**
 * open-gas kotoba — regulator + segment + leak registries + coverage.
 * AT PDS records (no RW). Segments reference an existing regulator; leaks
 * reference an existing segment.
 */

import type { Etzhayyim } from "@etzhayyim/sdk";
import {
  LEAK_COLLECTION,
  REGULATOR_COLLECTION,
  SEGMENT_COLLECTION,
  isLeakClass,
  leakDid,
  leakRkey,
  regulatorDid,
  regulatorRkey,
  segmentDid,
  segmentRkey,
  type CoverageInput,
  type CoverageOutput,
  type DefinePipeSegmentInput,
  type DefinePipeSegmentOutput,
  type DefineRegulatorInput,
  type DefineRegulatorOutput,
  type GetRegulatorInput,
  type GetRegulatorOutput,
  type GetSegmentInput,
  type GetSegmentOutput,
  type LeakRecord,
  type LeakView,
  type ListLeaksInput,
  type ListLeaksOutput,
  type ListRegulatorsInput,
  type ListRegulatorsOutput,
  type ListSegmentsInput,
  type ListSegmentsOutput,
  type PipeSegmentRecord,
  type PipeSegmentView,
  type RegulatorKind,
  type RegulatorRecord,
  type RegulatorView,
  type ReportLeakInput,
  type ReportLeakOutput,
  type SegmentStatus,
} from "./types.js";

const PAGE_LIMIT = 100;
const DEFAULT_MAX_SCAN = 10_000;
const REG_KINDS: ReadonlySet<RegulatorKind> = new Set(["cityGate", "district"]);

// ─── Regulator ──────────────────────────────────────────────────────

export async function defineRegulator(
  e: Etzhayyim,
  input: DefineRegulatorInput
): Promise<DefineRegulatorOutput> {
  if (!input.regulatorId || !input.name || !input.kind) {
    return { status: "rejected", error: "missingRequiredFields" };
  }
  if (!REG_KINDS.has(input.kind)) return { status: "rejected", error: "invalidKind" };

  const rkey = regulatorRkey(input.regulatorId);
  const existing = await e
    .read<RegulatorRecord>({ collection: REGULATOR_COLLECTION, rkey })
    .catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return {
      status: "alreadyExists",
      regulatorUri: existing.records[0].uri,
      did: existing.records[0].value.did,
      regulatorId: input.regulatorId,
    };
  }
  const did = regulatorDid(input.regulatorId);
  const record: RegulatorRecord = {
    did,
    regulatorId: input.regulatorId,
    name: input.name,
    kind: input.kind,
    outletPressureKpa: input.outletPressureKpa,
    location: input.location,
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.write({
    collection: REGULATOR_COLLECTION,
    record: record as unknown as Record<string, unknown>,
    rkey,
  });
  return { status: "defined", regulatorUri: receipt.uri, did, regulatorId: input.regulatorId };
}

export async function getRegulator(
  e: Etzhayyim,
  input: GetRegulatorInput
): Promise<GetRegulatorOutput> {
  if (!input.regulatorId) return { error: "invalidRegulatorId" };
  const resp = await e
    .read<RegulatorRecord>({ collection: REGULATOR_COLLECTION, rkey: regulatorRkey(input.regulatorId) })
    .catch(() => ({ records: [] }));
  const r = resp.records[0];
  if (!r) return { error: "notFound" };
  return { regulator: { ...r.value, regulatorUri: r.uri } };
}

export async function listRegulators(
  e: Etzhayyim,
  input: ListRegulatorsInput = {}
): Promise<ListRegulatorsOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<RegulatorRecord>({ collection: REGULATOR_COLLECTION, cursor: input.cursor, limit });
  const items: RegulatorView[] = resp.records
    .filter((r) => (input.kind ? r.value.kind === input.kind : true))
    .map((r) => ({ ...r.value, regulatorUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Pipe segment ───────────────────────────────────────────────────

export async function definePipeSegment(
  e: Etzhayyim,
  input: DefinePipeSegmentInput
): Promise<DefinePipeSegmentOutput> {
  if (!input.segmentId || !input.regulatorId) {
    return { status: "rejected", error: "missingRequiredFields" };
  }
  const reg = await e
    .read<RegulatorRecord>({ collection: REGULATOR_COLLECTION, rkey: regulatorRkey(input.regulatorId) })
    .catch(() => ({ records: [] }));
  if (!reg.records[0]?.value) return { status: "regulatorNotFound", error: "regulatorNotFound" };

  const rkey = segmentRkey(input.segmentId);
  const existing = await e
    .read<PipeSegmentRecord>({ collection: SEGMENT_COLLECTION, rkey })
    .catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return {
      status: "alreadyExists",
      segmentUri: existing.records[0].uri,
      did: existing.records[0].value.did,
      segmentId: input.segmentId,
    };
  }
  const did = segmentDid(input.segmentId);
  const record: PipeSegmentRecord = {
    did,
    segmentId: input.segmentId,
    regulatorId: input.regulatorId.toLowerCase(),
    dnMm: input.dnMm,
    material: input.material,
    maopKpa: input.maopKpa,
    lengthM: input.lengthM,
    status: input.status ?? "active",
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.write({
    collection: SEGMENT_COLLECTION,
    record: record as unknown as Record<string, unknown>,
    rkey,
  });
  return { status: "defined", segmentUri: receipt.uri, did, segmentId: input.segmentId };
}

export async function getSegment(
  e: Etzhayyim,
  input: GetSegmentInput
): Promise<GetSegmentOutput> {
  if (!input.segmentId) return { error: "invalidSegmentId" };
  const resp = await e
    .read<PipeSegmentRecord>({ collection: SEGMENT_COLLECTION, rkey: segmentRkey(input.segmentId) })
    .catch(() => ({ records: [] }));
  const r = resp.records[0];
  if (!r) return { error: "notFound" };
  return { segment: { ...r.value, segmentUri: r.uri } };
}

export async function listSegments(
  e: Etzhayyim,
  input: ListSegmentsInput = {}
): Promise<ListSegmentsOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<PipeSegmentRecord>({ collection: SEGMENT_COLLECTION, cursor: input.cursor, limit });
  const items: PipeSegmentView[] = resp.records
    .filter((r) => {
      const v = r.value;
      if (input.regulatorId && v.regulatorId !== input.regulatorId.toLowerCase()) return false;
      if (input.status && v.status !== input.status) return false;
      if (input.material && v.material !== input.material) return false;
      return true;
    })
    .map((r) => ({ ...r.value, segmentUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Leak ───────────────────────────────────────────────────────────

export async function reportLeak(
  e: Etzhayyim,
  input: ReportLeakInput
): Promise<ReportLeakOutput> {
  if (!input.leakId || !input.segmentId) {
    return { status: "rejected", error: "missingRequiredFields" };
  }
  if (!isLeakClass(input.leakClass)) return { status: "rejected", error: "invalidLeakClass" };

  const seg = await e
    .read<PipeSegmentRecord>({ collection: SEGMENT_COLLECTION, rkey: segmentRkey(input.segmentId) })
    .catch(() => ({ records: [] }));
  if (!seg.records[0]?.value) return { status: "segmentNotFound", error: "segmentNotFound" };

  const rkey = leakRkey(input.leakId);
  const existing = await e
    .read<LeakRecord>({ collection: LEAK_COLLECTION, rkey })
    .catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return {
      status: "alreadyExists",
      leakUri: existing.records[0].uri,
      did: existing.records[0].value.did,
      leakId: input.leakId,
    };
  }
  const did = leakDid(input.leakId);
  const now = new Date().toISOString();
  const record: LeakRecord = {
    did,
    leakId: input.leakId,
    segmentId: input.segmentId.toLowerCase(),
    leakClass: input.leakClass,
    status: "open",
    note: input.note,
    reportedAt: input.reportedAt ?? now,
    createdAt: now,
  };
  const receipt = await e.write({
    collection: LEAK_COLLECTION,
    record: record as unknown as Record<string, unknown>,
    rkey,
  });
  return { status: "reported", leakUri: receipt.uri, did, leakId: input.leakId };
}

export async function listLeaks(
  e: Etzhayyim,
  input: ListLeaksInput = {}
): Promise<ListLeaksOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<LeakRecord>({ collection: LEAK_COLLECTION, cursor: input.cursor, limit });
  const items: LeakView[] = resp.records
    .filter((r) => {
      const v = r.value;
      if (input.segmentId && v.segmentId !== input.segmentId.toLowerCase()) return false;
      if (input.status && v.status !== input.status) return false;
      // minClass: class 1 is most hazardous, so "min severity" = class <= minClass.
      if (typeof input.minClass === "number" && v.leakClass > input.minClass) return false;
      return true;
    })
    .map((r) => ({ ...r.value, leakUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Coverage ───────────────────────────────────────────────────────

async function countAll<T>(
  e: Etzhayyim,
  collection: string,
  maxScan: number,
  onRow: (v: T) => void
): Promise<number> {
  let cursor: string | undefined;
  let scanned = 0;
  while (scanned < maxScan) {
    const page = await e.read<T>({ collection, cursor, limit: PAGE_LIMIT });
    for (const r of page.records) {
      if (scanned >= maxScan) break;
      onRow(r.value);
      scanned += 1;
    }
    if (scanned >= maxScan || !page.cursor || page.records.length < PAGE_LIMIT) break;
    cursor = page.cursor;
  }
  return scanned;
}

export async function coverage(
  e: Etzhayyim,
  input: CoverageInput = {}
): Promise<CoverageOutput> {
  const maxScan = Math.min(input.maxScan ?? DEFAULT_MAX_SCAN, DEFAULT_MAX_SCAN);
  const regulatorCount = await countAll<RegulatorRecord>(e, REGULATOR_COLLECTION, maxScan, () => {});
  const segmentsByStatus: Record<string, number> = {};
  const segmentCount = await countAll<PipeSegmentRecord>(e, SEGMENT_COLLECTION, maxScan, (v) => {
    segmentsByStatus[v.status as SegmentStatus] = (segmentsByStatus[v.status as SegmentStatus] ?? 0) + 1;
  });
  let openHazardousLeaks = 0;
  const leakCount = await countAll<LeakRecord>(e, LEAK_COLLECTION, maxScan, (v) => {
    if (v.leakClass === 1 && v.status === "open") openHazardousLeaks += 1;
  });
  return {
    regulatorCount,
    segmentCount,
    segmentsByStatus,
    leakCount,
    openHazardousLeaks,
    truncated: regulatorCount >= maxScan || segmentCount >= maxScan || leakCount >= maxScan,
  };
}
