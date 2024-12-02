import dayjs from "dayjs";
import utc from "dayjs/plugin/utc";
import isString from "lodash/isString";

dayjs.extend(utc);

export const formatDate = (value: string, utc: boolean = false) => {
  const dateTimeFormat = "MM/DD/YY hh:mm A";
  if (isString(value) && dayjs(value).isValid()) {
    if (utc) {
      return dayjs(value).utc().format(dateTimeFormat);
    }

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
