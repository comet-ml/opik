/**
 * Calculates the percentage change between two values, suppressing
 * the trend when both values display identically at the formatter's precision.
 *
 * This prevents confusing users with a percentage change between
 * visually identical values (e.g. $0.0003 vs $0.0003).
 */
export const calcFormatterAwarePercentage = (
  current: number | undefined,
  baseline: number | undefined,
  formatter?: (v: number) => string,
): number | undefined => {
  if (current === undefined || baseline === undefined) {
    return undefined;
  }

  if (baseline === 0) {
    if (current === 0) return 0;
    return current > 0 ? Infinity : -Infinity;
  }

  if (formatter && formatter(current) === formatter(baseline)) return 0;

  return ((current - baseline) / Math.abs(baseline)) * 100;
};
