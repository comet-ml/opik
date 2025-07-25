import { DateRangeValue } from "@/components/shared/DateRangeSelect";
import dayjs from "dayjs";

export const DEFAULT_METRICS_DATE_RANGE: DateRangeValue = {
  from: dayjs().subtract(29, "days").startOf("day").toDate(),
  to: dayjs().endOf("day").toDate(),
};
export const MIN_METRICS_DATE = dayjs().subtract(1, "year").toDate();
export const MAX_METRICS_DATE = dayjs().toDate();
