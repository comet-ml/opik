import { useCallback, useMemo, useState } from "react";
import { DateRangeValue } from "@/shared/DateRangeSelect";
import {
  calculateIntervalBounds,
  IntervalBounds,
  reanchorIntervalBounds,
} from "./utils";

type ReanchoredBounds = {
  replaces: IntervalBounds;
  bounds: IntervalBounds;
};

export const useIntervalBounds = (dateRange: DateRangeValue) => {
  const selectionBounds = useMemo(
    () => calculateIntervalBounds(dateRange),
    [dateRange],
  );
  const [reanchored, setReanchored] = useState<ReanchoredBounds>();

  const bounds =
    reanchored?.replaces === selectionBounds
      ? reanchored.bounds
      : selectionBounds;

  const reanchorToNow = useCallback(() => {
    const next = reanchorIntervalBounds(dateRange, bounds);
    if (next === bounds) return false;

    setReanchored({ replaces: selectionBounds, bounds: next });
    return true;
  }, [dateRange, bounds, selectionBounds]);

  return { ...bounds, reanchorToNow };
};
