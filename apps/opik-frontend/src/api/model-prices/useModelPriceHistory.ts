import { QueryFunctionContext, useInfiniteQuery } from "@tanstack/react-query";
import api, {
  MODEL_PRICE_HISTORY_KEY,
  MODEL_PRICES_REST_ENDPOINT,
} from "@/api/api";
import { ModelPriceHistoryPage } from "@/lib/model-prices";

type UseModelPriceHistoryParams = {
  size: number;
};

const getModelPriceHistory = async (
  { signal }: QueryFunctionContext,
  { page, size }: UseModelPriceHistoryParams & { page: number },
) => {
  const { data } = await api.get<ModelPriceHistoryPage>(
    `${MODEL_PRICES_REST_ENDPOINT}/history`,
    { signal, params: { page, size } },
  );
  return data;
};

export default function useModelPriceHistory(
  params: UseModelPriceHistoryParams,
) {
  return useInfiniteQuery({
    queryKey: [MODEL_PRICE_HISTORY_KEY, params],
    queryFn: (context) =>
      getModelPriceHistory(context, {
        ...params,
        page: context.pageParam as number,
      }),
    initialPageParam: 1,
    // An empty page ends the list even if total says otherwise: a save between
    // two page loads changes total, and trusting it could ask for pages forever.
    getNextPageParam: (lastPage, allPages) => {
      if (!lastPage.content.length) return undefined;
      const loaded = allPages.reduce(
        (count, page) => count + page.content.length,
        0,
      );
      return loaded < lastPage.total ? allPages.length + 1 : undefined;
    },
  });
}
