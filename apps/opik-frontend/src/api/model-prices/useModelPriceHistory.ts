import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  MODEL_PRICE_HISTORY_KEY,
  MODEL_PRICES_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { ModelPriceHistoryPage } from "@/lib/model-prices";

type UseModelPriceHistoryParams = {
  page: number;
  size: number;
};

const getModelPriceHistory = async (
  { signal }: QueryFunctionContext,
  { page, size }: UseModelPriceHistoryParams,
) => {
  const { data } = await api.get<ModelPriceHistoryPage>(
    `${MODEL_PRICES_REST_ENDPOINT}/history`,
    { signal, params: { page, size } },
  );
  return data;
};

export default function useModelPriceHistory(
  params: UseModelPriceHistoryParams,
  options?: QueryConfig<ModelPriceHistoryPage>,
) {
  return useQuery({
    queryKey: [MODEL_PRICE_HISTORY_KEY, params],
    queryFn: (context) => getModelPriceHistory(context, params),
    ...options,
  });
}
