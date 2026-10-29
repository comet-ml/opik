/**
 * What the UI renders for a resolved cost.
 *
 * Mirrors `formatCost` in `apps/opik-frontend/src/lib/money.ts`, which is
 * `$` + lodash `floor(value, 2)` above a $0.01 floor, and the bare `noValue`
 * dash at or below zero.
 *
 * Several specs under `tests/trace-explore/` carry their own copy of this, and
 * two of them disagree with the app: a `toFixed(2)` version ROUNDS, so it reads
 * $0.03 where the page shows $0.02 and $0.07 where the page shows $0.06. Those
 * copies happen to sit on amounts where rounding and flooring agree, so they
 * are not wrong today — but the next spec that lands on a different amount
 * would be, which is why this one is shared rather than copied a fifth time.
 */

/** The floor below which `formatCost` stops naming an amount at all. */
export const MIN_DISPLAYED_COST = 0.01;

/**
 * lodash's `floor(value, 2)`, which is not `Math.floor(v * 100) / 100`.
 *
 * lodash shifts the decimal exponent in the number's STRING form, and the two
 * disagree exactly at the two-decimal boundaries a price lands on: `7.8 * 100`
 * is not exactly 780 in binary and `51.75 * 100` is under 5175, so the
 * multiply-and-divide form is a cent low for both.
 *
 * Split on `e` rather than appending to the literal, so a value JS stringifies
 * in exponential form (anything under 1e-6) shifts instead of becoming NaN.
 */
const floorToCents = (value: number): number => {
  const [mantissa, exponent] = `${value}e`.split('e');
  const shifted = Math.floor(Number(`${mantissa}e${Number(exponent || '0') + 2}`));
  return Number(`${shifted}e-2`);
};

/**
 * The exact string `formatCost` renders for `cost`, for an `{ exact: true }`
 * text lookup.
 *
 * Exact matters: `$3` must not pass for `$30`, and `$0.5` must not pass for
 * `$0.53`. Trailing zeros are dropped by JS number formatting, so $7.80 reads
 * as "$7.8" and $0.40 as "$0.4".
 */
export const displayedCost = (cost: number): string => {
  if (cost <= 0) {
    // `formatCost` renders the `noValue` dash here, not an amount — and the
    // dash is not a claim worth holding the UI to, so a spec asserting an
    // unpriced span must assert the ABSENCE of a dollar amount instead. Loud
    // rather than returning "<$0.01", which would be a plausible-looking
    // expectation that can never match.
    throw new Error(
      `displayedCost(${cost}): formatCost names no amount at or below zero. Assert that no dollar amount renders instead.`,
    );
  }
  if (cost < MIN_DISPLAYED_COST) {
    return `<$${MIN_DISPLAYED_COST}`;
  }
  return `$${floorToCents(cost)}`;
};
