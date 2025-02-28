import dayjs from "dayjs";
import utc from "dayjs/plugin/utc";
import relativeTime from "dayjs/plugin/relativeTime";
import isString from "lodash/isString";
import round from "lodash/round";
import isUndefined from "lodash/isUndefined";
import isNull from "lodash/isNull";

dayjs.extend(utc);
dayjs.extend(relativeTime);

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

export const getTimeFromNow = (date: string) => {
  if (isString(date) && dayjs(date).isValid()) {
    return dayjs().to(dayjs(date));
  }
  return "";
};

export const makeStartOfDay = (value: string) => {
  return dayjs(value).startOf("date").utcOffset(0, true).toISOString();
};

export const makeEndOfDay = (value: string) => {
  return dayjs(value).endOf("date").utcOffset(0, true).toISOString();
};

const millisecondsToSeconds = (milliseconds: number) => {
  // rounds with precision, one character after the point
  return round(milliseconds / 1000, 1);
};

export const secondsToMilliseconds = (seconds: number) => {
  return seconds * 1000;
};

export const formatDuration = (value?: number | null) => {
  return isUndefined(value) || isNull(value) || isNaN(value)
    ? "NA"
    : `${millisecondsToSeconds(value)}s`;
};
