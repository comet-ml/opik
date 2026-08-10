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
     * Set to false while an async-derived `defaultValue` is still loading, so the placeholder
     * default is not pinned into the URL ahead of the real one. See `initSyncReady` on
     * useQueryParamAndLocalStorageState.
     */
    initSyncReady?: boolean;
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
    initSyncReady,
    storageKeySuffix = "",
    ...rest
  } = options;

  // The caller's default has to reach the stored/fallback value too, not just the core hook:
  // the state below always resolves to something, so the core hook's own default would never
  // get a chance to apply.
  const defaultPreset = rest.defaultValue ?? DEFAULT_DATE_PRESET;

  const [value, setValue] = useQueryParamAndLocalStorageState<
    string | null | undefined
  >({
    localStorageKey: `${localStorageKey ?? `local-${key}`}${storageKeySuffix}`,
    queryKey: key,
    defaultValue: defaultPreset,
    queryParamConfig: StringParam,
    syncQueryWithLocalStorageOnInit: true,
    initSyncReady,
  });

  const rawValue = value ?? defaultPreset;
  const dateRangeValue = excludePresets?.includes(rawValue as DateRangePreset)
    ? defaultPreset
    : rawValue;

  return {
    ...useMetricDateRangeCore({
      value: dateRangeValue,
      setValue,
      ...rest,
    }),
    dateRangeValue,
  };
};
