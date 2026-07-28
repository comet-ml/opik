import { describe, it, expect } from "vitest";

import {
  getDotRadius,
  DOT_RADIUS_DEFAULT,
  DOT_RADIUS_BEST,
} from "./chartConstants";

describe("getDotRadius", () => {
  it("returns the default radius for a plain dot", () => {
    expect(getDotRadius({})).toBe(DOT_RADIUS_DEFAULT);
  });

  it("returns the larger radius for the best dot", () => {
    expect(getDotRadius({ isBest: true })).toBe(DOT_RADIUS_BEST);
  });

  it("grows a dot on hover", () => {
    expect(getDotRadius({ isHovered: true })).toBeGreaterThan(
      DOT_RADIUS_DEFAULT,
    );
  });

  it("grows the best dot on hover to the largest radius", () => {
    const bestHovered = getDotRadius({ isBest: true, isHovered: true });
    expect(bestHovered).toBeGreaterThan(DOT_RADIUS_BEST);
    expect(bestHovered).toBeGreaterThan(getDotRadius({ isHovered: true }));
  });
});
