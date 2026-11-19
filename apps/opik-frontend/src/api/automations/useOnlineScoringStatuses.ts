import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  AUTOMATIONS_KEY,
  AUTOMATIONS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { OnlineScoringStatus } from "@/types/automations";

type UseOnlineScoringStatusesParams = {
  traceId: string;
};

export type UseOnlineScoringStatusesResponse = {
  content: OnlineScoringStatus[];
};

export const getOnlineScoringStatuses = async (
  { signal }: QueryFunctionContext,
  { traceId }: UseOnlineScoringStatusesParams,
) => {
  const { data } = await api.get<UseOnlineScoringStatusesResponse>(
    `${AUTOMATIONS_REST_ENDPOINT}evaluators/scoring-statuses`,
    {
      signal,
      params: { trace_id: traceId },
    },
  );

  return data;
};

export const onlineScoringStatusesKey = (
  traceId: string,
): [string, { traceId: string }] => [
  `${AUTOMATIONS_KEY}-scoring-statuses`,
  { traceId },
];

export default function useOnlineScoringStatuses(
  params: UseOnlineScoringStatusesParams,
  options?: QueryConfig<UseOnlineScoringStatusesResponse>,
) {
  return useQuery({
    queryKey: onlineScoringStatusesKey(params.traceId),
    queryFn: (context) => getOnlineScoringStatuses(context, params),
    ...options,
  });
}
