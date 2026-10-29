import { describe, expect, it } from "vitest";
import { labelHash } from "@/lib/labelHash";
import { ChartTokens } from "./chartTokens";
import { assignSeriesColors } from "./seriesColors";

const palette = Array.from({ length: 10 }, (_, i) => `slot-${i}`);
const tokens = {
  palette,
  paletteStrong: palette.map((color) => `${color}-strong`),
  other: "other",
  otherStrong: "other-strong",
} as unknown as ChartTokens;

const colorsOf = (keys: string[], override?: (key: string) => string) =>
  assignSeriesColors(keys, tokens, { strong: false, override });

const homeSlot = (key: string) => `slot-${labelHash(key) % 10}`;

// Labels whose hashes land on different slots, and two that land on the same one.
const labels = Array.from({ length: 200 }, (_, i) => `model-${i}`);
const distinct = labels.reduce<string[]>(
  (picked, label) =>
    picked.length < 3 && !picked.some((p) => homeSlot(p) === homeSlot(label))
      ? [...picked, label]
      : picked,
  [],
);
const [first, collider] = labels.flatMap((a, i) =>
  labels
    .slice(i + 1)
    .flatMap((b) => (homeSlot(a) === homeSlot(b) ? [[a, b]] : [])),
)[0];

describe("assignSeriesColors", () => {
  it("gives a key its hashed palette slot, whatever else is in the chart", () => {
    const [a, b, c] = distinct;
    expect(colorsOf([a, b])).toEqual([homeSlot(a), homeSlot(b)]);
    expect(colorsOf([c, a])).toEqual([homeSlot(c), homeSlot(a)]);
  });

  it("moves a colliding key to the next free slot, independent of row order", () => {
    const forward = colorsOf([first, collider]);
    const backward = colorsOf([collider, first]);
    expect(new Set(forward).size).toBe(2);
    expect(backward).toEqual([forward[1], forward[0]]);
  });

  it("lets the workspace colour map override a key", () => {
    const [a, b] = distinct;
    expect(colorsOf([a, b], (key) => (key === a ? "#123456" : ""))).toEqual([
      "#123456",
      homeSlot(b),
    ]);
  });

  it("gives keys past the ten palette slots the other colour", () => {
    const colors = colorsOf(labels.slice(0, 12));
    expect(new Set(colors.filter((c) => c !== "other")).size).toBe(10);
    expect(colors.filter((c) => c === "other")).toHaveLength(2);
  });

  it("uses the strong variants for thin marks", () => {
    const [a] = distinct;
    expect(assignSeriesColors([a], tokens, { strong: true })).toEqual([
      `${homeSlot(a)}-strong`,
    ]);
  });
});
