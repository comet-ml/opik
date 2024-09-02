import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  FEEDBACK_DEFINITIONS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { FeedbackDefinition } from "@/types/feedback-definitions";

type UseFeedbackDefinitionsListParams = {
  workspaceName: string;
  search?: string;
  page: number;
  size: number;
};

export type FeedbackDefinitionsListResponse = {
  content: FeedbackDefinition[];
  total: number;
};

const getFeedbackDefinitionsList = async (
  { signal }: QueryFunctionContext,
  { workspaceName, search, size, page }: UseFeedbackDefinitionsListParams,
) => {
  const { data } = await api.get<FeedbackDefinitionsListResponse>(
    FEEDBACK_DEFINITIONS_REST_ENDPOINT,
    {
      signal,
      params: {
        workspace_name: workspaceName,
        ...(search && { name: search }),
        size,
        page,
      },
    },
  );

  return data;
};

export default function useFeedbackDefinitionsList(
  params: UseFeedbackDefinitionsListParams,
  options?: QueryConfig<FeedbackDefinitionsListResponse>,
) {
  return useQuery({
    queryKey: ["feedback-definitions", params],
    queryFn: (context) => getFeedbackDefinitionsList(context, params),
    ...options,
  });
}
