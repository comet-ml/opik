import { useCallback, useMemo } from "react";
import { isInteger, max, min } from "lodash";
import isNull from "lodash/isNull";
import { getDefaultChartYTickWidth } from "@/lib/charts";
import floor from "lodash/floor";
import { AxisDomain } from "recharts/types/util/types";

const DEFAULT_NUMBER_OF_TICKS = 5;
const DEFAULT_TICK_PRECISION = 6;

interface UseChartTickDefaultConfigProps {
  numberOfTicks?: number;
  tickPrecision?: number;
}

const generateEvenlySpacedValues = (
  a: number,
  b: number,
  n: number,
  isWithDecimals = true,
) => {
  if (n <= 2) return [a, b];

  const step = (b - a) / (n - 1);
  const values = [];

  for (let i = 0; i < n; i++) {
    const value = a + i * step;

    if (isNull(value)) {
      continue;
    }

    if (!isWithDecimals) {
      values.push(Math.round(value));
    } else {
      values.push(value);
    }
  }

  return values;
};

const DEFAULT_DOMAIN: AxisDomain = [0, "max"];

const useChartTickDefaultConfig = (
  values: (number | null)[],
  {
    numberOfTicks = DEFAULT_NUMBER_OF_TICKS,
    tickPrecision = DEFAULT_TICK_PRECISION,
  }: UseChartTickDefaultConfigProps = {},
) => {
  const filteredValues = useMemo(() => {
    return values.filter((v) => !isNull(v)) as number[];
  }, [values]);

  const areValuesWithDecimals = useMemo(() => {
    return values.some((v) => v !== null && !isInteger(v));
  }, [values]);

  const ticks = useMemo(() => {
    return generateEvenlySpacedValues(
      min([...filteredValues, 0]) as number,
      max(filteredValues) as number,
      numberOfTicks,
      areValuesWithDecimals,
    );
  }, [filteredValues, areValuesWithDecimals, numberOfTicks]);

  const tickWidth = useMemo(() => {
    return getDefaultChartYTickWidth({
      values: ticks,
      tickPrecision,
      includeDecimals: areValuesWithDecimals,
    });
  }, [values, areValuesWithDecimals]);

  const tickFormatter = useCallback(
    (value: number) => {
      return floor(value, tickPrecision).toString();
    },
    [areValuesWithDecimals],
  );

  return {
    width: tickWidth,
    ticks,
    tickFormatter,
    domain: DEFAULT_DOMAIN,
  };
};

export default useChartTickDefaultConfig;
