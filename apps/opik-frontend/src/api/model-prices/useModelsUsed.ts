import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  MODEL_PRICES_REST_ENDPOINT,
  MODELS_USED_KEY,
  QueryConfig,
} from "@/api/api";
import { ModelsUsed } from "@/lib/model-prices";

const getModelsUsed = async ({ signal }: QueryFunctionContext) => {
  const { data } = await api.get<ModelsUsed>(
    `${MODEL_PRICES_REST_ENDPOINT}/models-used`,
    { signal },
  );
  return data;
};

export default function useModelsUsed(options?: QueryConfig<ModelsUsed>) {
  return useQuery({
    queryKey: [MODELS_USED_KEY, {}],
    queryFn: (context) => getModelsUsed(context),
    ...options,
  });
}
