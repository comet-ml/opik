import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  LLM_MODEL_PROFILES_KEY,
  LLM_MODEL_PROFILES_REST_ENDPOINT,
} from "@/api/api";
import { ModelParamsByProvider } from "@/types/modelParams";

const getLlmModelProfiles = async ({
  signal,
}: QueryFunctionContext): Promise<ModelParamsByProvider> => {
  const { data } = await api.get<ModelParamsByProvider>(
    LLM_MODEL_PROFILES_REST_ENDPOINT,
    { signal },
  );
  return data;
};

export default function useLlmModelProfiles() {
  return useQuery({
    queryKey: [LLM_MODEL_PROFILES_KEY],
    queryFn: getLlmModelProfiles,
    staleTime: 5 * 60 * 1000,
    retry: 3,
    retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 10000),
  });
}
