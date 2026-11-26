import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  MODEL_PRICES_KEY,
  MODEL_PRICES_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { ModelPriceList } from "@/lib/model-prices";

const getModelPrices = async ({ signal }: QueryFunctionContext) => {
  const { data } = await api.get<ModelPriceList>(MODEL_PRICES_REST_ENDPOINT, {
    signal,
  });
  return data;
};

export default function useModelPrices(options?: QueryConfig<ModelPriceList>) {
  return useQuery({
    queryKey: [MODEL_PRICES_KEY, {}],
    queryFn: (context) => getModelPrices(context),
    ...options,
  });
}
