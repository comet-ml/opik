import useLocalStorageState from "use-local-storage-state";

export const DATE_FORMATS = {
  US_24H: "MM/DD/YY HH:mm",
  US_12H: "MM/DD/YY hh:mm A",
  EU_24H: "DD/MM/YY HH:mm",
  ISO: "YYYY-MM-DD HH:mm",
} as const;

export type DateFormatType = (typeof DATE_FORMATS)[keyof typeof DATE_FORMATS];

export const DATE_FORMAT_LABELS: Record<DateFormatType, string> = {
  [DATE_FORMATS.US_12H]: "MM/DD/YY hh:mm AM/PM",
  [DATE_FORMATS.EU_24H]: "DD/MM/YY HH:mm",
  [DATE_FORMATS.US_24H]: "MM/DD/YY HH:mm",
  [DATE_FORMATS.ISO]: "YYYY-MM-DD HH:mm",
};

export const DATE_FORMAT_EXAMPLES: Record<DateFormatType, string> = {
  [DATE_FORMATS.US_12H]: "01/26/26 03:45 PM",
  [DATE_FORMATS.EU_24H]: "26/01/26 15:45",
  [DATE_FORMATS.US_24H]: "01/26/26 15:45",
  [DATE_FORMATS.ISO]: "2026-01-26 15:45",
};

export const DATE_FORMAT_LOCAL_STORAGE_KEY = "opik-date-format";
const DEFAULT_DATE_FORMAT = DATE_FORMATS.US_12H;

export const getDateFormatFromLocalStorage = (): DateFormatType => {
  try {
    const stored = localStorage.getItem(DATE_FORMAT_LOCAL_STORAGE_KEY);
    if (stored) {
      const parsed = JSON.parse(stored);
      const validFormats = Object.values(DATE_FORMATS);
      if (parsed && validFormats.includes(parsed)) {
        return parsed;
      }
    }
  } catch {
    // Ignore parsing errors
  }
  return DEFAULT_DATE_FORMAT;
};

export const useDateFormat = () => {
  const [dateFormat, setDateFormat] = useLocalStorageState<DateFormatType>(
    DATE_FORMAT_LOCAL_STORAGE_KEY,
    {
      defaultValue: DEFAULT_DATE_FORMAT,
    },
  );

  return [dateFormat, setDateFormat] as const;
};

export const useDateFormatKey = () => {
  const [dateFormat] = useDateFormat();
  return `date-format-${dateFormat}`;
};
