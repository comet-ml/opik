import { useQuery } from "@tanstack/react-query";
import api, { TRACES_KEY } from "@/api/api";
import { FeedbackScoreGroup } from "@/types/traces";

type UseTraceFeedbackScoreGroupsParams = {
  traceId: string;
  workspaceName: string;
};

const getTraceFeedbackScoreGroups = async (
  { signal }: QueryFunctionContext,
  params: UseTraceFeedbackScoreGroupsParams,
) => {
  const { data } = await api.get(
    `/v1/private/traces/${params.traceId}/feedback-scores`,
    {
      signal,
      headers: {
        "X-Workspace-Name": params.workspaceName,
      },
    },
  );
  return data as FeedbackScoreGroup[];
};

export default function useTraceFeedbackScoreGroups(
  params: UseTraceFeedbackScoreGroupsParams,
  options?: QueryConfig<FeedbackScoreGroup[]>,
) {
  return useQuery({
    queryKey: [TRACES_KEY, "feedback-score-groups", params],
    queryFn: (context) => getTraceFeedbackScoreGroups(context, params),
    enabled: !!params.traceId && !!params.workspaceName,
    ...options,
  });
}