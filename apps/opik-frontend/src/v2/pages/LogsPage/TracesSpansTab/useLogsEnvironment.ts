import { useCallback, useEffect } from "react";
import useLocalStorageState from "use-local-storage-state";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";
import useEnvironmentsList from "@/api/environments/useEnvironmentsList";
import { TRACE_DATA_TYPE } from "@/constants/traces";
import { ENVIRONMENT_UNTAGGED_VALUE } from "@/lib/filters";
import {
  THREADS_FILTERS_URL_KEY,
  getLogsEnvironmentMemoryKey,
  getLogsFiltersUrlKey,
} from "@/v2/pages/LogsPage/TracesSpansTab/constants";

const TRACES_FILTERS_KEY = getLogsFiltersUrlKey(TRACE_DATA_TYPE.traces);
const SPANS_FILTERS_KEY = getLogsFiltersUrlKey(TRACE_DATA_TYPE.spans);

export const useLogsEnvironment = (projectId: string) => {
  const [urlEnvironment = "", setEnvironment] = useQueryParam(
    "environment",
    StringParam,
    { updateType: "replaceIn" },
  );

  // The environment param is shared by all Logs tabs, so any filter param means
  // the URL came from a link (or a tab switch) and is shown as-is.
  const [tracesFilters] = useQueryParam(TRACES_FILTERS_KEY, JsonParam);
  const [spansFilters] = useQueryParam(SPANS_FILTERS_KEY, JsonParam);
  const [threadsFilters] = useQueryParam(THREADS_FILTERS_URL_KEY, JsonParam);
  const canRestore =
    tracesFilters === undefined &&
    spansFilters === undefined &&
    threadsFilters === undefined;

  // Read synchronously, so the first render is already restored.
  const [saved, setSaved, { removeItem: removeSaved }] =
    useLocalStorageState<unknown>(getLogsEnvironmentMemoryKey(projectId), {
      storageSync: false,
    });
  const savedIsMalformed = saved !== undefined && typeof saved !== "string";
  const savedEnvironment = typeof saved === "string" ? saved : "";
  const environment = urlEnvironment || (canRestore ? savedEnvironment : "");

  const { data: environmentsData } = useEnvironmentsList();
  const envList = environmentsData?.content;

  const envIsValid = (() => {
    if (!environment) return null;
    if (environment === ENVIRONMENT_UNTAGGED_VALUE) return true;
    if (!envList) return null;
    return envList.some((e) => e.name === environment);
  })();

  // Re-runs on every URL change: re-clicking the page's own link doesn't remount it.
  useEffect(() => {
    if (!urlEnvironment && environment && envIsValid !== false) {
      setEnvironment(environment);
    }
  }, [urlEnvironment, environment, envIsValid, setEnvironment]);

  useEffect(() => {
    if (savedIsMalformed) removeSaved();
  }, [savedIsMalformed, removeSaved]);

  useEffect(() => {
    if (envIsValid !== false) return;
    // An invalid inbound value must not wipe a different remembered one.
    if (saved === environment) removeSaved();
    setEnvironment(undefined);
  }, [envIsValid, environment, saved, removeSaved, setEnvironment]);

  const changeEnvironment = useCallback(
    (next: string) => {
      if (next) setSaved(next);
      else removeSaved();
      setEnvironment(next || undefined);
    },
    [setSaved, removeSaved, setEnvironment],
  );

  return { environment, envIsValid, changeEnvironment };
};
