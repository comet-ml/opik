import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ChartTokens } from "./chartTokens";
import { assignSeriesColors } from "./seriesColors";

const palette = Array.from({ length: 10 }, (_, i) => `slot-${i}`);
const tokens = {
  palette,
  paletteStrong: palette,
  other: "other",
  otherStrong: "other",
} as unknown as ChartTokens;

const DAY = 86_400_000;

const colorsOf = (keys: string[], registryScope = "ws") =>
  assignSeriesColors(keys, tokens, {
    field: "model",
    strong: false,
    registryScope,
  });

const storedEntries = (registryScope = "ws") =>
  Object.keys(
    JSON.parse(
      localStorage.getItem(`opik-chart-entity-colors-v2:${registryScope}`) ??
        "{}",
    ).model ?? {},
  );

describe("assignSeriesColors entity registry", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.useFakeTimers();
    vi.setSystemTime(0);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("gives an entity the same colour in a later chart", () => {
    const [, claude] = colorsOf(["gpt-4", "claude"]);

    expect(colorsOf(["claude"])).toEqual([claude]);
  });

  it("stores at most one entity per palette slot", () => {
    for (let i = 0; i < 30; i += 1) colorsOf([`model-${i}`]);

    expect(storedEntries()).toHaveLength(10);
  });

  it("hands a new entity the slot of the one seen longest ago", () => {
    const keys = Array.from({ length: 10 }, (_, i) => `model-${i}`);
    const [oldest] = colorsOf(keys);

    vi.setSystemTime(DAY);
    colorsOf(keys.slice(1));
    vi.setSystemTime(2 * DAY);

    expect(colorsOf(["newcomer"])).toEqual([oldest]);
    expect(storedEntries()).not.toContain("model-0");
  });

  it("keeps workspaces apart", () => {
    colorsOf(["gpt-4", "claude"], "workspace-a");

    expect(colorsOf(["claude"], "workspace-b")).toEqual(["slot-0"]);
  });
});
