import isUndefined from "lodash/isUndefined";
import floor from "lodash/floor";
import isNull from "lodash/isNull";
import { formatNumberInK } from "@/lib/utils";

const MIN_DISPLAYED_COST = 0.001;
const CURRENCY = "$";
const PRECISION = 3;

type FormatCostOptions = {
  modifier?: "short" | "kFormat";
  noValue?: string;
  precision?: number;
};

export const formatCost = (
  value: number | string | undefined | null,
  { modifier, noValue = "-", precision = PRECISION }: FormatCostOptions = {},
) => {
  if (isUndefined(value) || isNull(value) || value === 0 || value === "0") {
    return noValue;
  }

  const numValue = Number(value);

  if (numValue < MIN_DISPLAYED_COST) {
    return `<${CURRENCY}${MIN_DISPLAYED_COST}`;
  }

  if (modifier === "kFormat") {
    return `${CURRENCY}${formatNumberInK(numValue, precision)}`;
  }

  if (modifier === "short") {
    return `${CURRENCY}${floor(numValue, precision)}`;
  }

  return `${CURRENCY}${value}`;
};
