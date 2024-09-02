import dayjs from "dayjs";
import utc from "dayjs/plugin/utc";

dayjs.extend(utc);

export const formatDate = (value: string) => {
  const dateTimeFormat = "MM/DD/YY hh:mm A";
  if (dayjs(value).isValid()) {
    return dayjs(value).format(dateTimeFormat);
  }
  return "";
};

export const makeStartOfDay = (value: string) => {
  return dayjs(value).startOf("date").utcOffset(0, true).toISOString();
};

export const makeEndOfDay = (value: string) => {
  return dayjs(value).endOf("date").utcOffset(0, true).toISOString();
};
