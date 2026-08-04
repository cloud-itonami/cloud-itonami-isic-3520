import { describe, it, expect, beforeEach } from "vitest";
import { MockEtzhayyim } from "@etzhayyim/sdk-mock";
import {
  defineRegulator,
  getRegulator,
  listRegulators,
  definePipeSegment,
  getSegment,
  listSegments,
  reportLeak,
  listLeaks,
  coverage,
} from "../src/index.js";

describe("open-gas kotoba", () => {
  let e: any;
  beforeEach(() => {
    e = new MockEtzhayyim({ did: "did:web:open-gas.etzhayyim.com" });
  });

  describe("regulator", () => {
    it("defines + gets + lists + rejects bad kind", async () => {
      expect((await defineRegulator(e, { regulatorId: "R-1", name: "Citygate North", kind: "cityGate", outletPressureKpa: 400 })).status).toBe("defined");
      expect((await getRegulator(e, { regulatorId: "R-1" })).regulator?.outletPressureKpa).toBe(400);
      expect((await listRegulators(e, { kind: "cityGate" })).total).toBe(1);
      expect((await defineRegulator(e, { regulatorId: "R-2", name: "x", kind: "valve" as any })).status).toBe("rejected");
    });
  });

  describe("segment (references regulator)", () => {
    beforeEach(async () => {
      await defineRegulator(e, { regulatorId: "R-1", name: "Citygate", kind: "cityGate" });
    });
    it("defines against an existing regulator; rejects missing", async () => {
      expect((await definePipeSegment(e, { segmentId: "S-1", regulatorId: "R-1", dnMm: 100, material: "pe", maopKpa: 400 })).status).toBe("defined");
      expect((await getSegment(e, { segmentId: "S-1" })).segment?.material).toBe("pe");
      expect((await definePipeSegment(e, { segmentId: "S-2", regulatorId: "NOPE" })).status).toBe("regulatorNotFound");
    });
    it("lists by regulator + status", async () => {
      await definePipeSegment(e, { segmentId: "S-1", regulatorId: "R-1", status: "active" });
      await definePipeSegment(e, { segmentId: "S-2", regulatorId: "R-1", status: "isolated" });
      expect((await listSegments(e, { regulatorId: "R-1" })).total).toBe(2);
      expect((await listSegments(e, { status: "isolated" })).total).toBe(1);
    });
  });

  describe("leak (DOT class) + coverage", () => {
    beforeEach(async () => {
      await defineRegulator(e, { regulatorId: "R-1", name: "Citygate", kind: "cityGate" });
      await definePipeSegment(e, { segmentId: "S-1", regulatorId: "R-1" });
    });
    it("reports against existing segment; rejects bad class/missing segment", async () => {
      expect((await reportLeak(e, { leakId: "L-1", segmentId: "S-1", leakClass: 1 })).status).toBe("reported");
      expect((await reportLeak(e, { leakId: "L-2", segmentId: "S-1", leakClass: 4 as any })).status).toBe("rejected");
      expect((await reportLeak(e, { leakId: "L-3", segmentId: "NOPE", leakClass: 2 })).status).toBe("segmentNotFound");
    });
    it("filters by class severity (minClass=1 → only class 1)", async () => {
      await reportLeak(e, { leakId: "L-1", segmentId: "S-1", leakClass: 1 });
      await reportLeak(e, { leakId: "L-2", segmentId: "S-1", leakClass: 3 });
      expect((await listLeaks(e, { minClass: 1 })).total).toBe(1);
      expect((await listLeaks(e, { segmentId: "S-1" })).total).toBe(2);
    });
    it("coverage rolls up network + open hazardous leaks", async () => {
      await reportLeak(e, { leakId: "L-1", segmentId: "S-1", leakClass: 1 });
      const cov = await coverage(e);
      expect(cov.regulatorCount).toBe(1);
      expect(cov.segmentCount).toBe(1);
      expect(cov.leakCount).toBe(1);
      expect(cov.openHazardousLeaks).toBe(1);
    });
  });
});
