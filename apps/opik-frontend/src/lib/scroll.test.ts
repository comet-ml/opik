import { describe, it, expect } from "vitest";

import { applyVerticalScrollRatio, getVerticalScrollRatio } from "./scroll";

const makeEl = (dims: {
  scrollTop: number;
  scrollHeight: number;
  clientHeight: number;
}): HTMLElement => dims as unknown as HTMLElement;

describe("getVerticalScrollRatio", () => {
  it("returns the position as a 0–1 ratio of the scrollable range", () => {
    expect(
      getVerticalScrollRatio(
        makeEl({ scrollTop: 50, scrollHeight: 200, clientHeight: 100 }),
      ),
    ).toBe(0.5);
  });

  it("returns 1 when the element is not scrollable", () => {
    expect(
      getVerticalScrollRatio(
        makeEl({ scrollTop: 0, scrollHeight: 100, clientHeight: 100 }),
      ),
    ).toBe(1);
  });
});

describe("applyVerticalScrollRatio", () => {
  it("maps a ratio onto the element's current scrollable range", () => {
    const el = makeEl({ scrollTop: 0, scrollHeight: 200, clientHeight: 100 });
    applyVerticalScrollRatio(el, 0.5);
    expect(el.scrollTop).toBe(50);
  });

  it("is the inverse of getVerticalScrollRatio for a scrollable element", () => {
    const source = makeEl({
      scrollTop: 30,
      scrollHeight: 330,
      clientHeight: 130,
    });
    const target = makeEl({
      scrollTop: 0,
      scrollHeight: 330,
      clientHeight: 130,
    });
    applyVerticalScrollRatio(target, getVerticalScrollRatio(source));
    expect(target.scrollTop).toBe(30);
  });
});
