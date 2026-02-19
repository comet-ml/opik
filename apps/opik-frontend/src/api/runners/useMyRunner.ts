import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  RUNNERS_KEY,
  RUNNERS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { Runner } from "@/types/runners";

const RUNNER_ID_KEY = "opik_runner_id";

export const getStoredRunnerId = () =>
  localStorage.getItem(RUNNER_ID_KEY) ?? undefined;

export const setStoredRunnerId = (id: string) =>
  localStorage.setItem(RUNNER_ID_KEY, id);

export const clearStoredRunnerId = () =>
  localStorage.removeItem(RUNNER_ID_KEY);

const getMyRunner = async (
  { signal }: QueryFunctionContext,
  runnerId: string,
) => {
  const { data } = await api.get<Runner>(
    `${RUNNERS_REST_ENDPOINT}${runnerId}`,
    { signal },
  );
  return data;
};

export default function useMyRunner(options?: QueryConfig<Runner | null>) {
  return useQuery({
    queryKey: [RUNNERS_KEY, "my"],
    queryFn: async (context) => {
      const runnerId = getStoredRunnerId();
      if (!runnerId) return null;
      try {
        return await getMyRunner(context, runnerId);
      } catch {
        return null;
      }
    },
    ...options,
  } as Parameters<typeof useQuery<Runner | null>>[0]);
}
