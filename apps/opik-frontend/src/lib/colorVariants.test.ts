import { describe, expect, it } from "vitest";

import {
  resolveChartColorMap,
  resolveColor,
  resolveHexColor,
} from "@/lib/colorVariants";
import { TAG_VARIANTS, TAG_VARIANTS_COLOR_MAP } from "@/ui/tag";
import { COLOR_VARIANTS_MAP } from "@/constants/colorVariants";

/**
 * Perceptual helpers live here rather than in shipped code: only these invariants need them, and
 * shipping an unused utility invites a second, divergent implementation.
 */
const linearize = (c: number) =>
  c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);

const linearChannels = (hex: string) =>
  [1, 3, 5].map((offset) =>
    linearize(parseInt(hex.slice(offset, offset + 2), 16) / 255),
  ) as [number, number, number];

const toLab = (hex: string): [number, number, number] => {
  const [r, g, b] = linearChannels(hex);

  const x = (r * 0.4124564 + g * 0.3575761 + b * 0.1804375) / 0.95047;
  const y = r * 0.2126729 + g * 0.7151522 + b * 0.072175;
  const z = (r * 0.0193339 + g * 0.119192 + b * 0.9503041) / 1.08883;

  const f = (t: number) => (t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116);

  return [116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z))];
};

/** CIE76 distance. Below ~25 two thin chart lines are hard to tell apart without hovering. */
const perceptualDistance = (a: string, b: string) => {
  const [l1, a1, b1] = toLab(a);
  const [l2, a2, b2] = toLab(b);
  return Math.hypot(l1 - l2, a1 - a2, b1 - b2);
};

const relativeLuminance = (hex: string) => {
  const [r, g, b] = linearChannels(hex);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};

const contrastRatio = (a: string, b: string) => {
  const la = relativeLuminance(a);
  const lb = relativeLuminance(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
};

// The grounds the product actually paints: `--background` is 0 0% 100% in the light theme and
// 0 0% 7% in the dark one (main.scss).
const LIGHT_GROUND = "#ffffff";
const DARK_GROUND = "#121212";

const autoPaletteHexes = () =>
  TAG_VARIANTS.map((variant) => ({
    variant: variant as string,
    hex: resolveHexColor(TAG_VARIANTS_COLOR_MAP[variant!]),
  }));

describe("automatic palette invariants", () => {
  it("resolves every entry to a hex value", () => {
    for (const { variant, hex } of autoPaletteHexes()) {
      expect(hex, `${variant} must round-trip to hex`).toMatch(
        /^#[0-9a-fA-F]{6}$/,
      );
    }
  });

  it("keeps every pair of entries perceptually distinguishable", () => {
    // The automatic color is `hash % TAG_VARIANTS.length`, so any two labels can land on any two
    // entries. Regression guard for OPIK-7839, where `primary` sat at ΔE 14.5 from `purple` and
    // made distinct grouped series look identical.
    const MINIMUM_DISTANCE = 30;
    const palette = autoPaletteHexes();
    const tooClose: string[] = [];

    for (let i = 0; i < palette.length; i += 1) {
      for (let j = i + 1; j < palette.length; j += 1) {
        const distance = perceptualDistance(palette[i].hex, palette[j].hex);
        if (distance < MINIMUM_DISTANCE) {
          tooClose.push(
            `${palette[i].variant}/${palette[j].variant} ΔE=${distance.toFixed(
              1,
            )}`,
          );
        }
      }
    }

    expect(tooClose).toEqual([]);
  });

  it("keeps the palette at ten entries", () => {
    // The automatic color is `hash % TAG_VARIANTS.length`. The golden test below samples four
    // labels, which would not notice a length change that only moves labels it does not sample,
    // so the length is pinned separately. Changing it re-colors every label in the product.
    expect(TAG_VARIANTS).toHaveLength(10);
  });

  it("pins the design-approved hex for the entry excused from the contrast check", () => {
    // `purpleDark` is skipped by the contrast assertion below, so without this the approved value
    // could drift to something the check would otherwise have rejected.
    expect(COLOR_VARIANTS_MAP.purpleDark.hex.toLowerCase()).toBe("#491b7e");
    expect(resolveHexColor(COLOR_VARIANTS_MAP.purpleDark.css)).toBe(
      COLOR_VARIANTS_MAP.purpleDark.hex,
    );
  });

  it("never assigns the indigo that was confusable with purple and blue", () => {
    const indigo = COLOR_VARIANTS_MAP.primary.hex.toLowerCase();

    expect(
      autoPaletteHexes().map(({ hex }) => hex.toLowerCase()),
    ).not.toContain(indigo);
  });

  it("keeps known labels on the colors they resolve to today", () => {
    // Guards the property that makes a palette change safe to ship: the automatic color depends on
    // the palette length, so adding or removing an entry silently re-colors every label in the
    // product. Substituting one entry only moves labels that resolved to that slot. If this fails,
    // a change shifted labels it did not intend to.
    expect(
      resolveChartColorMap([
        "domain-agent",
        "supervisor-agent",
        "planner-agent",
        "retriever-agent",
      ]),
    ).toEqual({
      "domain-agent": "var(--color-purple-dark)",
      "supervisor-agent": "var(--color-blue)",
      "planner-agent": "var(--color-orange)",
      "retriever-agent": "var(--color-yellow)",
    });
  });

  it("still lets two labels share a color once the palette is exhausted", () => {
    // Documented limitation, not a defect to fix here: with ten colors, identical colors remain
    // possible as the number of series approaches the palette size. The answer is the manual
    // per-label override, not automatic reassignment — reassigning would break the guarantee that
    // a label keeps one color across widgets.
    const map = resolveChartColorMap(["id_filter_enabled", "domain-agent"]);

    expect(map["id_filter_enabled"]).toBe(map["domain-agent"]);
  });

  it("keeps entries legible as thin lines on both grounds", () => {
    // WCAG 1.4.11 asks for 3:1 and names "each line in a graph", so that is the floor here.
    // Five of the ten entries do not meet it and are tracked as a palette-contrast follow-up:
    //   light ground — yellow 1.92, turquoise 2.43, green 2.54, orange 2.80 (all pre-existing)
    //   dark ground  — purpleDark 1.55 (design-chosen; reads well on light, but is nearly the
    //                  dark theme's own background)
    // Half the palette failing is the finding, not an accident of this test: a single palette
    // serving both themes cannot clear the floor on both, which is why the follow-up proposes two
    // lightness bands. The assertion still blocks *new* entries from joining the list, and the
    // list must not grow.
    const KNOWN_LOW_CONTRAST = [
      "yellow",
      "turquoise",
      "green",
      "orange",
      "purpleDark",
    ];
    const MINIMUM_CONTRAST = 3;
    const failing: string[] = [];

    for (const { variant, hex } of autoPaletteHexes()) {
      if (KNOWN_LOW_CONTRAST.includes(variant)) continue;

      const light = contrastRatio(hex, LIGHT_GROUND);
      const dark = contrastRatio(hex, DARK_GROUND);
      if (Math.min(light, dark) < MINIMUM_CONTRAST) {
        failing.push(
          `${variant} light=${light.toFixed(2)} dark=${dark.toFixed(2)}`,
        );
      }
    }

    expect(failing).toEqual([]);
  });
});

describe("resolveColor priority", () => {
  const label = "domain-agent";

  it("falls back to an automatic color when no map contains the label", () => {
    expect(resolveColor(label)).toBeDefined();
  });

  it("prefers a caller-supplied color over the automatic one", () => {
    const reserved = { [label]: COLOR_VARIANTS_MAP.gray.css };

    expect(resolveColor(label, null, reserved)).toBe(
      COLOR_VARIANTS_MAP.gray.css,
    );
    expect(resolveColor(label, null, reserved)).not.toBe(resolveColor(label));
  });

  it("prefers the workspace color over both the caller-supplied and the automatic one", () => {
    const workspace = { [label]: "#123456" };
    const reserved = { [label]: COLOR_VARIANTS_MAP.gray.css };

    expect(resolveColor(label, workspace, reserved)).toBe("#123456");
  });

  it("leaves labels absent from the maps on their automatic color", () => {
    const other = "supervisor-agent";
    const reserved = { [label]: COLOR_VARIANTS_MAP.gray.css };

    expect(resolveColor(other, null, reserved)).toBe(resolveColor(other));
  });

  it("assigns a label the same color regardless of which other labels are present", () => {
    // Cross-widget consistency is a product requirement, not an incidental property: the same tag
    // must keep one color across widgets that show different sets of series.
    const inOneChart = resolveChartColorMap([label, "supervisor-agent"]);
    const inAnother = resolveChartColorMap([label, "planner-agent", "a", "b"]);

    expect(inOneChart[label]).toBe(inAnother[label]);
    expect(resolveChartColorMap([label])[label]).toBe(inOneChart[label]);
  });
});

describe("resolveChartColorMap", () => {
  it("returns one color per label", () => {
    const labels = ["alpha", "beta", "gamma"];
    const map = resolveChartColorMap(labels);

    expect(Object.keys(map).sort()).toEqual(labels.sort());
  });

  it("returns an empty map for an empty label list", () => {
    expect(resolveChartColorMap([])).toEqual({});
  });

  it("resolves an empty-string label to a palette color rather than undefined", () => {
    // Group-by values can be empty strings; a missing color makes recharts fall back to black.
    expect(resolveChartColorMap([""])[""]).toMatch(/^var\(--color-/);
  });

  it("leaves a value that is already hex untouched and passes unknown values through", () => {
    expect(resolveHexColor("#123abc")).toBe("#123abc");
    expect(resolveHexColor("var(--color-not-a-real-token)")).toBe(
      "var(--color-not-a-real-token)",
    );
  });

  it("pins only the labels the caller names, leaving the rest automatic", () => {
    // How a chart fixes colors for specific series (metric sub-series today) without disturbing
    // labels it says nothing about.
    const pinned = { traces: COLOR_VARIANTS_MAP.purple.css };
    const map = resolveChartColorMap(["traces", "domain-agent"], null, pinned);

    expect(map.traces).toBe(COLOR_VARIANTS_MAP.purple.css);
    expect(map["domain-agent"]).toBe(resolveColor("domain-agent"));
  });
});
