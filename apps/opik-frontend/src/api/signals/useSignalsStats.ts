import { useQuery } from "@tanstack/react-query";
import { QueryConfig, SIGNALS_STATS_KEY } from "@/api/api";
import { SignalsStats } from "@/types/signals";
import { MOCK_SIGNALS_STATS, mockDelay } from "@/api/signals/mockSignalsData";

type UseSignalsStatsParams = {
  projectId: string;
};

// TODO(signals-backend): replace the mock body with a real request, e.g.
//   const { data } = await api.get<SignalsStats>(
//     `${PROJECTS_REST_ENDPOINT}${projectId}/signals/stats`,
//     { signal },
//   );
//   return data;
const getSignalsStats = async (): Promise<SignalsStats> =>
  mockDelay(MOCK_SIGNALS_STATS);

export default function useSignalsStats(
  params: UseSignalsStatsParams,
  options?: QueryConfig<SignalsStats>,
) {
  return useQuery({
    queryKey: [SIGNALS_STATS_KEY, params],
    queryFn: () => getSignalsStats(),
    ...options,
  });
}
