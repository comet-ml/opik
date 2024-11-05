import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROMPTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { PromptWithLatestVersion } from "@/types/prompts";

// ALEX
const FAKE_PROMPT = {
  id: "p001",
  name: "Product Recommendation Prompt",
  description:
    "A prompt designed to generate product recommendations based on user preferences.",
  last_updated_at: "2024-11-04T14:35:00Z",
  created_at: "2024-10-20T09:00:00Z",
  versions_count: 3,

  latest_version: {
    id: "v003",
    created_at: "2024-11-04T14:35:00Z",
    template:
      "Recommend products to {{username}} based on preferences in {{category}} and previous interactions.",
    variables: ["username", "category"],
  },
};

const getPromptById = async (
  { signal }: QueryFunctionContext,
  { promptId }: UsePromptByIdParams,
) => {
  try {
    const { data } = await api.get(`${PROMPTS_REST_ENDPOINT}/${promptId}`, {
      signal,
    });

    return data;
  } catch {
    return FAKE_PROMPT;
  }
};

type UsePromptByIdParams = {
  promptId: string;
};

export default function usePromptById(
  params: UsePromptByIdParams,
  options?: QueryConfig<PromptWithLatestVersion>,
) {
  return useQuery({
    queryKey: ["prompt", params],
    queryFn: (context) => getPromptById(context, params),
    ...options,
  });
}
