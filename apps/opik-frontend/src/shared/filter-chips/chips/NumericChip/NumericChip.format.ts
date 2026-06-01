import {
  NumericChipDefinition,
  NumericChipFormat,
} from "@/shared/filter-chips/types";

export interface NumericFormat {
  prefix?: string;
  suffix?: string;
  decimals: number;
  integerOnly: boolean;
  placeholder: string;
}

const PRESETS: Record<NumericChipFormat, NumericFormat> = {
  integer: {
    decimals: 0,
    integerOnly: true,
    placeholder: "100",
  },
  decimal: {
    decimals: 2,
    integerOnly: false,
    placeholder: "0.00",
  },
  currency: {
    prefix: "$",
    decimals: 2,
    integerOnly: false,
    placeholder: "0.00",
  },
  duration: {
    suffix: "s",
    decimals: 1,
    integerOnly: false,
    placeholder: "0.0",
  },
};

export const resolveNumericFormat = (
  definition: NumericChipDefinition,
): NumericFormat => PRESETS[definition.format ?? "decimal"];

export const formatNumericValue = (
  value: number,
  format: NumericFormat,
): string => {
  const body =
    format.decimals > 0 ? value.toFixed(format.decimals) : `${value}`;
  return `${format.prefix ?? ""}${body}${format.suffix ?? ""}`;
};
