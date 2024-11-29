import isUndefined from "lodash/isUndefined";
import floor from "lodash/floor";

const MIN_DISPLAYED_COST = 0.001;
const CURRENCY = "$";
const PRECISION = 3;

export const formatCost = (
  value: number | string | undefined,
  short = false,
) => {
  if (isUndefined(value)) {
    return "-";
  }

  const numValue = Number(value);

  if (short && numValue < MIN_DISPLAYED_COST) {
    return `<${CURRENCY}${MIN_DISPLAYED_COST}`;
  }

  if (short) {
    return `${CURRENCY}${floor(numValue, PRECISION)}`;
  }

  return `${CURRENCY}${value}`;
};
