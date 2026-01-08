import { useCallback, useMemo } from "react";
import { AxisDomain, AxisInterval } from "recharts/types/util/types";
import isInteger from "lodash/isInteger";
import isNull from "lodash/isNull";

import { getTextWidth } from "@/lib/utils";

const DEFAULT_NUMBER_OF_TICKS = 5;
const DEFAULT_MAX_PRECISION = 6;
const MIN_Y_AXIS_WIDTH = 26;
const MAX_Y_AXIS_WIDTH = 80;
const Y_AXIS_EXTRA_WIDTH = 10;
const DOMAIN_PADDING_RATIO = 0.1;

const DEFAULT_DOMAIN: AxisDomain = [0, "max"];
const DEFAULT_INTERVAL: AxisInterval = "preserveStartEnd";

const getOrderOfMagnitude = (value: number): number => {
  if (value === 0) return 0;
  return Math.floor(Math.log10(Math.abs(value)));
};

const getRequiredPrecision = (value: number): number => {
  if (value === 0 || isInteger(value)) return 0;
  const abs = Math.abs(value);
  if (abs >= 1) return 2;
  return Math.abs(getOrderOfMagnitude(abs)) + 2;
};

const formatTickValue = (value: number, precision: number = 0): string => {
  if (precision === 0 || isInteger(value)) {
    return Math.round(value).toString();
  }
  const fixed = value.toFixed(precision);
  return fixed.replace(/\.?0+$/, "") || "0";
};

const generateNiceTicks = (
  min: number,
  max: number,
  targetCount: number,
): number[] => {
  if (targetCount <= 2) return [min, max];

  const range = max - min;
  if (range === 0) return [min];

  const roughStep = range / (targetCount - 1);
  const magnitude = Math.pow(10, getOrderOfMagnitude(roughStep));
  const normalizedStep = roughStep / magnitude;

  let niceMultiplier: number;
  if (normalizedStep <= 1) niceMultiplier = 1;
  else if (normalizedStep <= 2) niceMultiplier = 2;
  else if (normalizedStep <= 2.5) niceMultiplier = 2.5;
  else if (normalizedStep <= 5) niceMultiplier = 5;
  else niceMultiplier = 10;

  const niceStep = niceMultiplier * magnitude;
  const precision = Math.max(0, -getOrderOfMagnitude(niceStep));

  const ticks: number[] = [];
  let current = Math.floor(min / niceStep) * niceStep;

  while (!(current > max && current - max > niceStep * 0.2)) {
    ticks.push(parseFloat(current.toFixed(precision)));
    current += niceStep;
  }

  return ticks;
};

interface UseChartTickDefaultConfigProps {
  numberOfTicks?: number;
  tickPrecision?: number;
  maxTickPrecision?: number;
  tickFormatter?: (value: number, precision?: number) => string;
  showMinMaxDomain?: boolean;
}

interface ChartTickConfig {
  width: number;
  ticks: number[];
  yTickFormatter: (value: number) => string;
  domain: AxisDomain;
  interval: AxisInterval;
}

const useChartTickDefaultConfig = (
  values: (number | null)[],
  {
    numberOfTicks = DEFAULT_NUMBER_OF_TICKS,
    tickPrecision,
    maxTickPrecision = DEFAULT_MAX_PRECISION,
    tickFormatter = formatTickValue,
    showMinMaxDomain = false,
  }: UseChartTickDefaultConfigProps = {},
): ChartTickConfig => {
  const filteredValues = useMemo(
    () => values.filter((v): v is number => !isNull(v)),
    [values],
  );

  const stats = useMemo(() => {
    if (filteredValues.length === 0) {
      return { min: 0, max: 0, hasValues: false };
    }
    return {
      min: Math.min(...filteredValues),
      max: Math.max(...filteredValues),
      hasValues: true,
    };
  }, [filteredValues]);

  const displayPrecision = useMemo(() => {
    if (tickPrecision !== undefined) return tickPrecision;

    if (!filteredValues.some((v) => !isInteger(v))) return 0;

    const calculatedPrecision = filteredValues.reduce(
      (maxLen, v) => Math.max(getRequiredPrecision(v), maxLen),
      0,
    );

    return Math.min(calculatedPrecision, maxTickPrecision);
  }, [filteredValues, tickPrecision, maxTickPrecision]);

  const { domain, tickRange } = useMemo(() => {
    if (!stats.hasValues) {
      return {
        domain: DEFAULT_DOMAIN,
        tickRange: null,
      };
    }

    if (showMinMaxDomain) {
      const range = stats.max - stats.min;
      const padding = range * DOMAIN_PADDING_RATIO;
      const domainMin = Math.max(0, stats.min - padding);
      const domainMax = stats.max + padding;

      return {
        domain: [domainMin, domainMax] as [number, number],
        tickRange: [domainMin, domainMax] as const,
      };
    }

    return {
      domain: DEFAULT_DOMAIN,
      tickRange: [Math.min(stats.min, 0), stats.max] as const,
    };
  }, [stats, showMinMaxDomain]);

  const ticks = useMemo(() => {
    if (!tickRange) return [];

    const [rangeMin, rangeMax] = tickRange;
    return generateNiceTicks(rangeMin, rangeMax, numberOfTicks);
  }, [tickRange, numberOfTicks]);

  const width = useMemo(() => {
    if (ticks.length === 0) return MIN_Y_AXIS_WIDTH;

    const formattedTicks = ticks.map((value) =>
      tickFormatter(value, displayPrecision),
    );
    const maxTickWidth = Math.max(
      ...getTextWidth(formattedTicks, { font: "10px Inter" }),
    );
    const calculatedWidth = maxTickWidth + Y_AXIS_EXTRA_WIDTH;

    return Math.max(
      MIN_Y_AXIS_WIDTH,
      Math.min(MAX_Y_AXIS_WIDTH, calculatedWidth),
    );
  }, [ticks, tickFormatter, displayPrecision]);

  const yTickFormatter = useCallback(
    (value: number) => tickFormatter(value, displayPrecision),
    [displayPrecision, tickFormatter],
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
