/**
 * open-gas kotoba — record types.
 *
 * Per ADR-2605203000 Option B. Gas utility network: regulator nodes + pipe
 * segments + leaks. Registry on AT PDS records (replaces D1 nodes/segments/leaks).
 * ADR-2605172000 kotoba.
 *
 * Identity hierarchy:
 *   did:web:open-gas.etzhayyim.com                          — controller
 *   did:web:open-gas.etzhayyim.com:regulator:{regulatorId} — a regulator node
 *   did:web:open-gas.etzhayyim.com:segment:{segmentId}     — a pipe segment
 *   did:web:open-gas.etzhayyim.com:leak:{leakId}           — a leak report
 */

export const OGAS_DID_PREFIX = "did:web:open-gas.etzhayyim.com:" as const;

export const REGULATOR_COLLECTION = "com.etzhayyim.apps.openGas.regulator";
export const SEGMENT_COLLECTION = "com.etzhayyim.apps.openGas.segment";
export const LEAK_COLLECTION = "com.etzhayyim.apps.openGas.leak";

// ─── Regulator ──────────────────────────────────────────────────────

export type RegulatorKind = "cityGate" | "district";

export interface RegulatorRecord {
  did: string;
  regulatorId: string;
  name: string;
  kind: RegulatorKind;
  /** Outlet pressure in kPa (integer). */
  outletPressureKpa?: number;
  location?: string;
  createdAt: string;
}

export interface RegulatorView extends RegulatorRecord {
  regulatorUri: string;
}

export interface DefineRegulatorInput {
  regulatorId: string;
  name: string;
  kind: RegulatorKind;
  outletPressureKpa?: number;
  location?: string;
}

export interface DefineRegulatorOutput {
  status: "defined" | "alreadyExists" | "rejected";
  regulatorUri?: string;
  did?: string;
  regulatorId?: string;
  error?: string;
}

export interface GetRegulatorInput {
  regulatorId: string;
}

export interface GetRegulatorOutput {
  regulator?: RegulatorView;
  error?: string;
}

export interface ListRegulatorsInput {
  kind?: RegulatorKind;
  limit?: number;
  cursor?: string;
}

export interface ListRegulatorsOutput {
  items: RegulatorView[];
  cursor?: string;
  total: number;
}

// ─── Pipe segment ───────────────────────────────────────────────────

export type PipeMaterial = "steel" | "pe" | "castIron" | "copper" | "other";
export type SegmentStatus = "active" | "isolated" | "abandoned";

export interface PipeSegmentRecord {
  did: string;
  segmentId: string;
  /** Upstream regulator. */
  regulatorId: string;
  /** Nominal diameter, mm (integer). */
  dnMm?: number;
  material?: PipeMaterial;
  /** Max allowable operating pressure, kPa (integer). */
  maopKpa?: number;
  lengthM?: number;
  status: SegmentStatus;
  createdAt: string;
}

export interface PipeSegmentView extends PipeSegmentRecord {
  segmentUri: string;
}

export interface DefinePipeSegmentInput {
  segmentId: string;
  regulatorId: string;
  dnMm?: number;
  material?: PipeMaterial;
  maopKpa?: number;
  lengthM?: number;
  status?: SegmentStatus;
}

export interface DefinePipeSegmentOutput {
  status: "defined" | "alreadyExists" | "rejected" | "regulatorNotFound";
  segmentUri?: string;
  did?: string;
  segmentId?: string;
  error?: string;
}

export interface GetSegmentInput {
  segmentId: string;
}

export interface GetSegmentOutput {
  segment?: PipeSegmentView;
  error?: string;
}

export interface ListSegmentsInput {
  regulatorId?: string;
  status?: SegmentStatus;
  material?: PipeMaterial;
  limit?: number;
  cursor?: string;
}

export interface ListSegmentsOutput {
  items: PipeSegmentView[];
  cursor?: string;
  total: number;
}

// ─── Leak (DOT class 1/2/3) ─────────────────────────────────────────

/** DOT-aligned leak grade: 1 = hazardous (immediate), 2, 3 = non-hazardous. */
export type LeakClass = 1 | 2 | 3;
export type LeakStatus = "open" | "monitored" | "repaired";

export interface LeakRecord {
  did: string;
  leakId: string;
  segmentId: string;
  leakClass: LeakClass;
  status: LeakStatus;
  note?: string;
  reportedAt: string;
  createdAt: string;
}

export interface LeakView extends LeakRecord {
  leakUri: string;
}

export interface ReportLeakInput {
  leakId: string;
  segmentId: string;
  leakClass: LeakClass;
  note?: string;
  reportedAt?: string;
}

export interface ReportLeakOutput {
  status: "reported" | "alreadyExists" | "rejected" | "segmentNotFound";
  leakUri?: string;
  did?: string;
  leakId?: string;
  error?: string;
}

export interface ListLeaksInput {
  segmentId?: string;
  minClass?: LeakClass;
  status?: LeakStatus;
  limit?: number;
  cursor?: string;
}

export interface ListLeaksOutput {
  items: LeakView[];
  cursor?: string;
  total: number;
}

export interface CoverageInput {
  maxScan?: number;
}

export interface CoverageOutput {
  regulatorCount?: number;
  segmentCount?: number;
  segmentsByStatus?: Record<string, number>;
  leakCount?: number;
  openHazardousLeaks?: number;
  truncated?: boolean;
  error?: string;
}

// ─── Helpers ────────────────────────────────────────────────────────

export function isLeakClass(n: number): n is LeakClass {
  return n === 1 || n === 2 || n === 3;
}

export function regulatorDid(id: string): string {
  return `${OGAS_DID_PREFIX}regulator:${id.toLowerCase()}`;
}
export function regulatorRkey(id: string): string {
  return `regulator-${id.toLowerCase()}`;
}
export function segmentDid(id: string): string {
  return `${OGAS_DID_PREFIX}segment:${id.toLowerCase()}`;
}
export function segmentRkey(id: string): string {
  return `segment-${id.toLowerCase()}`;
}
export function leakDid(id: string): string {
  return `${OGAS_DID_PREFIX}leak:${id.toLowerCase()}`;
}
export function leakRkey(id: string): string {
  return `leak-${id.toLowerCase()}`;
}
