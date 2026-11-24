import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  MODEL_PRICE_RESOLVE_KEY,
  MODEL_PRICES_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import {
  ModelPriceResolution,
  ModelPriceResolveRequest,
} from "@/lib/model-prices";

const RESOLVE_STALE_TIME = 5 * 60 * 1000;

const resolveModelPrice = async (
  { signal }: QueryFunctionContext,
  request: ModelPriceResolveRequest,
) => {
  const { data } = await api.post<ModelPriceResolution>(
    `${MODEL_PRICES_REST_ENDPOINT}/resolve`,
    request,
    { signal },
  );
  return data;
};

export default function useModelPriceResolve(
  { model, provider, usage }: ModelPriceResolveRequest,
  options?: QueryConfig<ModelPriceResolution>,
) {
  const request = { model, provider, usage };
  return useQuery({
    queryKey: [MODEL_PRICE_RESOLVE_KEY, request],
    queryFn: (context) => resolveModelPrice(context, request),
    staleTime: RESOLVE_STALE_TIME,
    ...options,
  });
}
