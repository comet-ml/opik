import { useQuery, QueryFunctionContext } from "@tanstack/react-query";
import api, { LLM_MODELS_REST_ENDPOINT, LLM_MODELS_KEY } from "@/api/api";

export type LlmModelDefinition = {
  id: string;
  qualifiedName?: string;
  label?: string;
  structuredOutput: boolean;
  reasoning: boolean;
};

export type LlmModelsByProvider = Record<string, LlmModelDefinition[]>;

const getLlmModels = async ({
  signal,
}: QueryFunctionContext): Promise<LlmModelsByProvider> => {
  const { data } = await api.get<LlmModelsByProvider>(
    LLM_MODELS_REST_ENDPOINT,
    { signal },
  );
  return data;
};

export default function useLlmModels() {
  return useQuery({
    queryKey: [LLM_MODELS_KEY],
    queryFn: getLlmModels,
    staleTime: 5 * 60 * 1000,
    // Global QueryClient has retry: false; for the model list we want retries
    // since the dropdown is useless without data. One transient failure would
    // otherwise break every provider dropdown in the app.
    retry: 3,
    retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 10000),
  });
}
