import { useCallback, useMemo } from "react";
import { AxisDomain, AxisInterval } from "recharts/types/util/types";
import isInteger from "lodash/isInteger";
import max from "lodash/max";
import min from "lodash/min";
import isNull from "lodash/isNull";
import uniq from "lodash/uniq";

import { getTextWidth } from "@/lib/utils";

const defaultTickFormatter = (value: number, maxDecimalLength?: number) =>
  maxDecimalLength ? value.toFixed(maxDecimalLength) : value.toString();

const DEFAULT_NUMBER_OF_TICKS = 5;
const DEFAULT_TICK_PRECISION = 6;
const MIN_Y_AXIS_WIDTH = 26;
const MAX_Y_AXIS_WIDTH = 80;
const Y_AXIS_EXTRA_WIDTH = 10;

interface UseChartTickDefaultConfigProps {
  numberOfTicks?: number;
  tickPrecision?: number;
  tickFormatter?: (value: number, maxDecimalLength?: number) => string;
  showMinMaxDomain?: boolean;
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

  return uniq(values);
};

const DEFAULT_DOMAIN: AxisDomain = [0, "max"];
const DEFAULT_INTERVAL: AxisInterval = "preserveStartEnd";

const useChartTickDefaultConfig = (
  values: (number | null)[],
  {
    numberOfTicks = DEFAULT_NUMBER_OF_TICKS,
    tickPrecision = DEFAULT_TICK_PRECISION,
    tickFormatter = defaultTickFormatter,
    showMinMaxDomain = false,
  }: UseChartTickDefaultConfigProps = {},
) => {
  const filteredValues = useMemo(() => {
    return values.filter((v) => !isNull(v)) as number[];
  }, [values]);

  const maxDecimalNumbersLength = useMemo(() => {
    if (!filteredValues.some((v) => !isInteger(v))) return 0;

    return filteredValues.reduce<number>((maxLen, v) => {
      const partition = v.toString().split(".");

      if (partition.length > 1) {
        const decimals = partition[1];

        return Math.min(Math.max(decimals.length, maxLen), tickPrecision);
      }

      return maxLen;
    }, 0);
  }, [filteredValues, tickPrecision]);

  const numericDomain = useMemo<[number, number] | null>(() => {
    if (!showMinMaxDomain || filteredValues.length === 0) return null;

    const minValue = (min(filteredValues) ?? 0) as number;
    const maxValue = (max(filteredValues) ?? 0) as number;
    const range = maxValue - minValue;
    const padding = range * 0.1;
    const domainMin = Math.max(0, minValue - padding);
    const domainMax = maxValue + padding;

    return [domainMin, domainMax];
  }, [filteredValues, showMinMaxDomain]);

  const domain: AxisDomain = useMemo(() => {
    if (numericDomain) return numericDomain as AxisDomain;
    if (!showMinMaxDomain) return DEFAULT_DOMAIN;
    return [0, "max"] as AxisDomain;
  }, [numericDomain, showMinMaxDomain]);

  const ticks = useMemo(() => {
    if (filteredValues.length === 0) {
      return [] as number[];
    }

    if (showMinMaxDomain && numericDomain) {
      const [domainMin, domainMax] = numericDomain;
      return generateEvenlySpacedValues(
        domainMin,
        domainMax,
        numberOfTicks,
        Boolean(maxDecimalNumbersLength),
      );
    }

    return generateEvenlySpacedValues(
      min([...filteredValues, 0]) as number,
      max(filteredValues) as number,
      numberOfTicks,
      Boolean(maxDecimalNumbersLength),
    );
  }, [
    numericDomain,
    filteredValues,
    maxDecimalNumbersLength,
    numberOfTicks,
    showMinMaxDomain,
  ]);

  const width = useMemo(() => {
    if (!ticks.length) {
      return MIN_Y_AXIS_WIDTH;
    }
    return Math.min(
      Math.max(
        Math.max(
          ...getTextWidth(
            ticks.map((value) => tickFormatter(value, maxDecimalNumbersLength)),
            { font: "10px Inter" },
          ),
        ) + Y_AXIS_EXTRA_WIDTH,
        MIN_Y_AXIS_WIDTH,
      ),
      MAX_Y_AXIS_WIDTH,
    );
  }, [ticks, tickFormatter, maxDecimalNumbersLength]);

  const yTickFormatter = useCallback(
    (value: number) => tickFormatter(value, maxDecimalNumbersLength),
    [maxDecimalNumbersLength, tickFormatter],
  );

  return {
    width,
    ticks,
    yTickFormatter,
    domain,
    interval: DEFAULT_INTERVAL,
  };
};

export default useChartTickDefaultConfig;
