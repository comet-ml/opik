/**
 * What the UI renders for a duration in milliseconds.
 *
 * Mirrors `formatDuration(value)` in `apps/opik-frontend/src/lib/date.ts`,
 * whose default `onlySeconds` branch is
 * `${formatMillisecondsAsSeconds(ms)}s`, i.e. lodash
 * `round(ms / 1000, ms > 100 ? 1 : ms > 10 ? 2 : 3)`.
 *
 * Shared rather than copied, at the invitation of the comment this moved from
 * (`experiments/experiments-group-row-aggregates.spec.ts`): "if a second
 * duration assertion lands, this moves". It lives in its own module rather
 * than beside `displayedCost` in `core/money.ts`, which that comment named —
 * that file's own header is specifically about what `formatCost` renders, and a
 * duration has nothing to do with money beyond both being numbers a header
 * cell formats.
 *
 * Mirrored rather than approximated, because the assertion is an exact text
 * comparison: a `toFixed(1)` version reads "4.0s" where the page shows "4.2s",
 * and trailing zeros are dropped by JS number formatting, so a round 4000ms
 * renders "4s" rather than "4.0s" and a p50 of 1250ms renders "1.3s".
 */

/**
 * lodash's `round(value, precision)`, which is not `Math.round(v * f) / f`.
 *
 * lodash shifts the decimal exponent in the number's STRING form, so it rounds
 * the decimal the user sees rather than its binary approximation; the two
 * disagree exactly at the half-way boundaries a percentile lands on. Taken
 * from `floorToCents` in `core/money.ts`, which exists for the same reason on
 * the floor side, and split on `e` rather than appended to the literal so a
 * value JS stringifies in exponential form shifts instead of becoming NaN.
 */
const roundLikeLodash = (value: number, precision: number): number => {
  const [mantissa, exponent] = `${value}e`.split('e');
  const shifted = Math.round(Number(`${mantissa}e${Number(exponent || '0') + precision}`));
  return Number(`${shifted}e-${precision}`);
};

/**
 * The exact string `formatDuration` renders for `milliseconds`, for an
 * `{ exact: true }` text lookup or a `toHaveText` comparison.
 *
 * Loud on a negative input rather than returning a plausible-looking string: a
 * duration below zero is a seed or an arithmetic error, and a spec that
 * compared against "-0.5s" would be holding the UI to an expectation it can
 * never meet.
 */
export const displayedDuration = (milliseconds: number): string => {
  if (!Number.isFinite(milliseconds) || milliseconds < 0) {
    throw new Error(
      `displayedDuration(${milliseconds}): not a duration. formatDuration renders "NA" for a ` +
        'non-number; assert that instead of comparing against a formatted amount.',
    );
  }
  const precision = milliseconds > 100 ? 1 : milliseconds > 10 ? 2 : 3;
  return `${roundLikeLodash(milliseconds / 1000, precision)}s`;
};
