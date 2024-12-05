import { useCallback, useMemo } from "react";
import isInteger from "lodash/isInteger";
import max from "lodash/max";
import min from "lodash/min";
import isNull from "lodash/isNull";
import { getDefaultChartYTickWidth } from "@/lib/charts";
import floor from "lodash/floor";
import { AxisDomain, AxisInterval } from "recharts/types/util/types";

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
const DEFAULT_INTERVAL: AxisInterval = "preserveStartEnd";

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
    return filteredValues.some((v) => !isInteger(v));
  }, [filteredValues]);

  const maxDecimalNumbersLength = useMemo(() => {
    if (!areValuesWithDecimals) {
      return 0;
    }

    return filteredValues.reduce<number>((maxLen, v) => {
      const partition = v.toString().split(".");

      if (partition.length > 1) {
        const decimals = partition[1];

        return Math.min(Math.max(decimals.length, maxLen), tickPrecision);
      }

      return maxLen;
    }, 0);
  }, [areValuesWithDecimals, filteredValues, tickPrecision]);

  const ticks = useMemo(() => {
    return generateEvenlySpacedValues(
      min([...filteredValues, 0]) as number,
      max(filteredValues) as number,
      numberOfTicks,
      areValuesWithDecimals,
    );
  }, [filteredValues, areValuesWithDecimals, numberOfTicks]);

  const areTicksWithDecimals = useMemo(() => {
    return ticks.some((v: number) => !isInteger(floor(v, tickPrecision)));
  }, [ticks, tickPrecision]);

  const tickWidth = useMemo(() => {
    return getDefaultChartYTickWidth({
      values: ticks,
      tickPrecision,
      withDecimals: areTicksWithDecimals,
    });
  }, [areTicksWithDecimals, tickPrecision, ticks]);

  const tickFormatter = useCallback(
    (value: number) => {
      if (areTicksWithDecimals) {
        return value.toFixed(maxDecimalNumbersLength);
      }

      return value.toString();
    },
    [areTicksWithDecimals, maxDecimalNumbersLength],
  );

  return {
    width: tickWidth,
    ticks,
    tickFormatter,
    domain: DEFAULT_DOMAIN,
    interval: DEFAULT_INTERVAL,
  };
};

export default useChartTickDefaultConfig;
