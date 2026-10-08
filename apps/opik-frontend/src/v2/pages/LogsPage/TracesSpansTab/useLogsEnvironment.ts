import { useCallback, useEffect } from "react";
import useLocalStorageState from "use-local-storage-state";
import { StringParam, useQueryParam } from "use-query-params";
import useEnvironmentsList from "@/api/environments/useEnvironmentsList";
import { ENVIRONMENT_UNTAGGED_VALUE } from "@/lib/filters";
import { getLogsEnvironmentMemoryKey } from "@/v2/pages/LogsPage/TracesSpansTab/constants";

type UseLogsEnvironmentOptions = {
  // False when the URL arrived with filters from a link: show it as-is.
  canRestore: boolean;
};

export const useLogsEnvironment = (
  projectId: string,
  { canRestore }: UseLogsEnvironmentOptions,
) => {
  const [urlEnvironment = "", setEnvironment] = useQueryParam(
    "environment",
    StringParam,
    { updateType: "replaceIn" },
  );

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
