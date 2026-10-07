import { useCallback, useEffect, useMemo } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import useEnvironmentsList from "@/api/environments/useEnvironmentsList";
import { ENVIRONMENT_UNTAGGED_VALUE } from "@/lib/filters";
import { createSessionStorageMemory } from "@/lib/sessionStorageMemory";
import { getLogsEnvironmentMemoryKey } from "@/v2/pages/LogsPage/TracesSpansTab/constants";

export const useLogsEnvironment = (projectId: string) => {
  const [environment = "", setEnvironment] = useQueryParam(
    "environment",
    StringParam,
    { updateType: "replaceIn" },
  );

  const memory = useMemo(
    () =>
      createSessionStorageMemory<string>(
        getLogsEnvironmentMemoryKey(projectId),
      ),
    [projectId],
  );

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
    if (environment) return;
    const saved = memory.load();
    if (saved) setEnvironment(saved);
  }, [environment, memory, setEnvironment]);

  // Also forget the value, otherwise the restore above would bring it back.
  useEffect(() => {
    if (envIsValid === false) {
      memory.save(undefined);
      setEnvironment(undefined);
    }
  }, [envIsValid, memory, setEnvironment]);

  const changeEnvironment = useCallback(
    (next: string) => {
      memory.save(next);
      setEnvironment(next || undefined);
    },
    [memory, setEnvironment],
  );

  return { environment, envIsValid, changeEnvironment };
};
