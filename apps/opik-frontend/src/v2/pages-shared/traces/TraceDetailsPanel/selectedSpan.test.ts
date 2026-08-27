import { describe, it, expect } from "vitest";
import { Span } from "@/types/traces";
import { findSelectedSpan } from "./selectedSpan";

const span = (id: string) => ({ id }) as Span;

describe("findSelectedSpan", () => {
  it("returns the loaded span the id names", () => {
    expect(findSelectedSpan("b", [span("a"), span("b")])).toEqual(span("b"));
  });

  it("returns nothing when no span is selected", () => {
    expect(findSelectedSpan("", [span("a")])).toBeUndefined();
    expect(findSelectedSpan(null, [span("a")])).toBeUndefined();
  });

  it("returns nothing while the spans are still loading", () => {
    expect(findSelectedSpan("a", undefined)).toBeUndefined();
  });

  it("returns nothing when the id names no loaded span", () => {
    expect(findSelectedSpan("z", [span("a"), span("b")])).toBeUndefined();
  });
});
