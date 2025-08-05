import { useQuery } from "@tanstack/react-query";
import api, { SPANS_KEY } from "@/api/api";
import { FeedbackScoreGroup } from "@/types/traces";

type UseSpanFeedbackScoreGroupsParams = {
  spanId: string;
  workspaceName: string;
};

const getSpanFeedbackScoreGroups = async (
  { signal }: QueryFunctionContext,
  params: UseSpanFeedbackScoreGroupsParams,
) => {
  const { data } = await api.get(
    `/v1/private/spans/${params.spanId}/feedback-scores`,
    {
      signal,
      headers: {
        "X-Workspace-Name": params.workspaceName,
      },
    },
  );
  return data as FeedbackScoreGroup[];
};

export default function useSpanFeedbackScoreGroups(
  params: UseSpanFeedbackScoreGroupsParams,
  options?: QueryConfig<FeedbackScoreGroup[]>,
) {
  return useQuery({
    queryKey: [SPANS_KEY, "feedback-score-groups", params],
    queryFn: (context) => getSpanFeedbackScoreGroups(context, params),
    enabled: !!params.spanId && !!params.workspaceName,
    ...options,
  });
}