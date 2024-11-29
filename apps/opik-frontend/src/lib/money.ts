import isUndefined from "lodash/isUndefined";

const MIN_DISPLAYED_COST = 0.001;
const CURRENCY = "$";

export const formatCost = (
  value: number | string | undefined,
  short = false,
) => {
  if (isUndefined(value)) {
    return "-";
  }

  if (short && Number(value) < MIN_DISPLAYED_COST) {
    return `<${CURRENCY}${MIN_DISPLAYED_COST}`;
  }

  return `${CURRENCY}${value}`;
};
