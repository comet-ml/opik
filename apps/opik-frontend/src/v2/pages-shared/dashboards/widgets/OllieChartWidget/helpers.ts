const DEFAULT_TITLE = "Ollie chart";

export const widgetHelpers = {
  getDefaultConfig: () => ({}),
  calculateTitle: (config: Record<string, unknown>) =>
    (config.description as string | undefined) || DEFAULT_TITLE,
};

const HOUR = 60 * 60_000;
const DAY = 24 * HOUR;

const plural = (count: number, unit: string) =>
  `${count} ${unit}${count === 1 ? "" : "s"}`;

export type ComparisonWindow = {
  intervalStart: string;
  intervalEnd: string;
  // "24 hours", "30 days": what the change is compared with.
  label: string;
};

// The window of the same length just before a dashboard range, which a KPI's change compares with. A preset range has
// no end: it runs to `now`.
export const comparisonWindow = (
  intervalStart: string,
  intervalEnd: string | undefined,
  now: number,
): ComparisonWindow | null => {
  const start = new Date(intervalStart).getTime();
  const end = intervalEnd ? new Date(intervalEnd).getTime() : now;
  const length = end - start;
  if (!Number.isFinite(length) || length <= 0) return null;
  // Under two days the window reads in hours ("Past 24 hours" starts on the hour, so it is 24 hours and a part of
  // one). Longer ranges start at midnight and count today's part as a day ("Past 30 days" is 29 days and today).
  // A closed range ends a millisecond before its last midnight, so it rounds to the hour; an open one has run past it.
  const hours = intervalEnd
    ? Math.round(length / HOUR)
    : Math.floor(length / HOUR);
  const label =
    length < 2 * DAY
      ? plural(Math.max(1, hours), "hour")
      : plural(
          start % DAY === 0
            ? Math.ceil(length / DAY)
            : Math.round(length / DAY),
          "day",
        );
  return {
    intervalStart: new Date(start - length).toISOString(),
    intervalEnd: intervalStart,
    label,
  };
};
