import { useCallback, useEffect, useMemo, useReducer } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import useEnvironmentsList from "@/api/environments/useEnvironmentsList";
import { ENVIRONMENT_UNTAGGED_VALUE } from "@/lib/filters";
import { createSessionStorageMemory } from "@/lib/sessionStorageMemory";
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
  const [, forceRender] = useReducer((n: number) => n + 1, 0);

  const memory = useMemo(
    () =>
      createSessionStorageMemory<string>(
        getLogsEnvironmentMemoryKey(projectId),
      ),
    [projectId],
  );

  // Read on every render: the first render is already restored, and a forgotten value stays gone.
  const environment = urlEnvironment || (canRestore ? memory.load() ?? "" : "");

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
    if (envIsValid !== false) return;
    // An invalid inbound value must not wipe a different remembered one.
    if (memory.load() === environment) memory.save(undefined);
    setEnvironment(undefined);
    // A restored value isn't in the URL, so clearing it there may not re-render.
    forceRender();
  }, [envIsValid, environment, memory, setEnvironment]);

  const changeEnvironment = useCallback(
    (next: string) => {
      memory.save(next);
      setEnvironment(next || undefined);
    },
    [memory, setEnvironment],
  );

  return { environment, envIsValid, changeEnvironment };
};
