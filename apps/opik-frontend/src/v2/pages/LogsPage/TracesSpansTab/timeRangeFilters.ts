import { COLUMN_TYPE } from "@/types/shared";
import { Filters } from "@/types/filters";

export const createStartTimeRangeFilters = (
  intervalStart?: string,
  intervalEnd?: string,
): Filters => {
  const filters: Filters = [];

  if (intervalStart) {
    filters.push({
      id: "logs_start_time_from_range",
      field: "start_time",
      type: COLUMN_TYPE.time,
      operator: ">=",
      value: intervalStart,
    });
  }

  if (intervalEnd) {
    filters.push({
      id: "logs_start_time_to_range",
      field: "start_time",
      type: COLUMN_TYPE.time,
      operator: "<=",
      value: intervalEnd,
    });
  }

  return filters;
};
