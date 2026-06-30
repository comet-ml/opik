import { describe, it, expect } from "vitest";
import { finiteOrNull } from "./createExplainTargetBuilder";

// finiteOrNull is the single coercion point for every explain metric payload:
// it must only ever emit a backend-valid `number | null` (the backend models
// are `float | None` with `ge=0`), never `undefined`, NaN, Infinity, or a
// negative — any of which the backend rejects with a 422 "Invalid payload".
describe("finiteOrNull", () => {
  it("passes finite non-negative numbers through (including 0)", () => {
    expect(finiteOrNull(0)).toBe(0);
    expect(finiteOrNull(0.0021)).toBe(0.0021);
    expect(finiteOrNull(4200)).toBe(4200);
  });

  it("maps absent / non-finite values to null", () => {
    expect(finiteOrNull(undefined)).toBeNull();
    expect(finiteOrNull(null)).toBeNull();
    expect(finiteOrNull(NaN)).toBeNull();
    expect(finiteOrNull(Infinity)).toBeNull();
    expect(finiteOrNull(-Infinity)).toBeNull();
  });

  it("maps negatives to null (backend requires >= 0; never let it 422)", () => {
    expect(finiteOrNull(-5)).toBeNull();
    expect(finiteOrNull(-0.0001)).toBeNull();
  });

  it("coerces numeric strings (a non-conforming API value) instead of nulling a real value", () => {
    expect(finiteOrNull("5")).toBe(5);
    expect(finiteOrNull("0.0021")).toBe(0.0021);
    expect(finiteOrNull("-5")).toBeNull();
    expect(finiteOrNull("abc")).toBeNull();
  });
});
