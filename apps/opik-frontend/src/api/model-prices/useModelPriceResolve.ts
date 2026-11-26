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

const RESOLVE_STALE_TIME = 30 * 1000;

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
  const query = useQuery({
    queryKey: [MODEL_PRICE_RESOLVE_KEY, request],
    queryFn: (context) => resolveModelPrice(context, request),
    staleTime: RESOLVE_STALE_TIME,
    ...options,
  });
  // Prices can change in another tab or through the API, and the card says whether a span's prices are
  // "Still current", so an old cached answer is held back until the refetch confirms or replaces it.
  const isRevalidating = query.isFetching && query.isStale;
  return { ...query, isPending: query.isPending || isRevalidating };
}
