import { StringParam } from "use-query-params";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import {
  useMetricDateRangeCore,
  UseMetricDateRangeOptions,
} from "./useMetricDateRangeCore";
import { DEFAULT_DATE_PRESET, DEFAULT_DATE_URL_KEY } from "./constants";
import { DateRangePreset } from "@/shared/DateRangeSelect";

type UseMetricDateRangeWithQueryAndStorageOptions =
  UseMetricDateRangeOptions & {
    key?: string;
    localStorageKey?: string;
    excludePresets?: DateRangePreset[];
    /**
     * Appended to the resolved localStorage key, giving a caller its own persistence slot under an
     * otherwise shared key.
     *
     * The stored range is deliberately sticky across projects, which means a stored value outranks
     * any `defaultValue` — so a project that needs a different default (the demo project needs 24h)
     * would otherwise inherit whatever range the user last picked elsewhere and never see it. A
     * suffix keeps that project's range in its own slot, so the default applies and the user's
     * choice there does not leak back to other projects.
     */
    storageKeySuffix?: string;
  };

export const useMetricDateRangeWithQueryAndStorage = (
  options: UseMetricDateRangeWithQueryAndStorageOptions = {},
) => {
  const {
    key = DEFAULT_DATE_URL_KEY,
    localStorageKey,
    excludePresets,
    // Entity-scoped views (experiment Logs tab, playground, trials, annotation queues) are already
    // narrowed to a single entity, so the trailing 30-day window only hides older traces. They pass
    // their own default; an explicitly chosen range still wins over it.
    defaultValue = DEFAULT_DATE_PRESET,
    storageKeySuffix = "",
    ...rest
  } = options;

  const [value, setValue] = useQueryParamAndLocalStorageState<
    string | null | undefined
  >({
    localStorageKey: `${localStorageKey ?? `local-${key}`}${storageKeySuffix}`,
    queryKey: key,
    defaultValue,
    queryParamConfig: StringParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const rawValue = value ?? defaultValue;
  const dateRangeValue = excludePresets?.includes(rawValue as DateRangePreset)
    ? defaultValue
    : rawValue;

  return {
    ...useMetricDateRangeCore({
      value: dateRangeValue,
      setValue,
      defaultValue,
      ...rest,
    }),
    dateRangeValue,
  };
};
