import { useQuery } from "@tanstack/react-query";
import { QueryConfig } from "@/api/api";
import { BuiltInPrice, parseBuiltInPrices } from "@/lib/model-prices";

const BUILT_IN_PRICES_KEY = "built-in-model-prices";

const loadBuiltInPrices = async () => {
  const file = await import("@/data/model_prices_and_context_window.json");
  return parseBuiltInPrices(file.default);
};

export default function useBuiltInPrices(
  options?: QueryConfig<BuiltInPrice[]>,
) {
  return useQuery({
    queryKey: [BUILT_IN_PRICES_KEY, {}],
    queryFn: loadBuiltInPrices,
    staleTime: Infinity,
    gcTime: Infinity,
    ...options,
  });
}
