import { describe, expect, it } from "vitest";
import { comparisonWindow } from "./helpers";

const HOUR = 60 * 60_000;
const DAY = 24 * HOUR;
const midnight = Date.UTC(2026, 9, 1);

describe("comparisonWindow", () => {
  it("compares an open-ended preset with the window just before it, up to the moment it was read", () => {
    // "Past 30 days": 29 days back from today's midnight, running to now (10:00).
    const start = new Date(midnight - 29 * DAY).toISOString();
    const now = midnight + 10 * HOUR;

    const window = comparisonWindow(start, undefined, now)!;

    expect(window.intervalEnd).toBe(start);
    expect(
      new Date(start).getTime() - new Date(window.intervalStart).getTime(),
    ).toBe(now - new Date(start).getTime());
    expect(window.label).toBe("30 days");
  });

  it("moves with the range when it is read again later", () => {
    const start = new Date(midnight - 29 * DAY).toISOString();

    const first = comparisonWindow(start, undefined, midnight + 10 * HOUR)!;
    const later = comparisonWindow(start, undefined, midnight + 13 * HOUR)!;

    expect(
      new Date(first.intervalStart).getTime() -
        new Date(later.intervalStart).getTime(),
    ).toBe(3 * HOUR);
    expect(later.intervalEnd).toBe(first.intervalEnd);
  });

  it.each([
    [
      "on the hour",
      midnight + 9 * HOUR - DAY,
      midnight + 9 * HOUR + 20 * 60_000,
    ],
    ["at midnight", midnight - DAY, midnight + 40 * 60_000],
  ])("reads the past 24 hours in hours when it starts %s", (_, start, now) => {
    expect(
      comparisonWindow(new Date(start).toISOString(), undefined, now)!.label,
    ).toBe("24 hours");
  });

  it("counts a closed range in days and refuses an empty one", () => {
    const start = new Date(midnight).toISOString();
    const end = new Date(midnight + 7 * DAY - 1).toISOString();

    expect(comparisonWindow(start, end, 0)!.label).toBe("7 days");
    // A closed day ends a millisecond before midnight and still reads as a whole day.
    expect(
      comparisonWindow(start, new Date(midnight + DAY - 1).toISOString(), 0)!
        .label,
    ).toBe("24 hours");
    expect(comparisonWindow(start, start, 0)).toBeNull();
  });
});
