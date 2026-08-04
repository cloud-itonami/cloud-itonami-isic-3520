/**
 * open-gas kotoba — barrel.
 *
 * Per ADR-2605203000 Option B. Gas utility network registry on the etzhayyim
 * substrate (AT PDS records; no RW).
 *
 *   regulator : defineRegulator / getRegulator / listRegulators
 *   segment   : definePipeSegment / getSegment / listSegments
 *   leak      : reportLeak / listLeaks
 *   coverage  : network + open-hazardous-leak rollup
 */

export * from "./types.js";
export {
  defineRegulator,
  getRegulator,
  listRegulators,
  definePipeSegment,
  getSegment,
  listSegments,
  reportLeak,
  listLeaks,
  coverage,
} from "./registry.js";
