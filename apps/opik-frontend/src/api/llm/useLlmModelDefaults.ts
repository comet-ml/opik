import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  LLM_MODEL_DEFAULTS_KEY,
  LLM_MODEL_DEFAULTS_REST_ENDPOINT,
} from "@/api/api";

export type LlmModelDefaults = Record<string, string>;

const getLlmModelDefaults = async ({
  signal,
}: QueryFunctionContext): Promise<LlmModelDefaults> => {
  const { data } = await api.get<LlmModelDefaults>(
    LLM_MODEL_DEFAULTS_REST_ENDPOINT,
    { signal },
  );
  return data;
};

export default function useLlmModelDefaults() {
  return useQuery({
    queryKey: [LLM_MODEL_DEFAULTS_KEY],
    queryFn: getLlmModelDefaults,
    staleTime: 5 * 60 * 1000,
  });
}
