import { QueryConfig } from "@/api/api";
import useSpansList, {
  UseSpansListParams,
  UseSpansListResponse,
} from "@/api/traces/useSpansList";

const MAX_FULL_DATA_SPANS = 500;

export default function useLazySpansList(
  params: UseSpansListParams,
  options?: QueryConfig<UseSpansListResponse>,
) {
  const lightQuery = useSpansList(
    { ...params, exclude: ["input", "output"] },
    options,
  );

  const canLoadFullData =
    !lightQuery.isPlaceholderData &&
    lightQuery.data?.total !== undefined &&
    lightQuery.data.total <= MAX_FULL_DATA_SPANS;

  const fullQuery = useSpansList(params, {
    ...options,
    enabled: canLoadFullData && (options?.enabled ?? true),
  });

  const query =
    canLoadFullData && fullQuery.data && !fullQuery.isPlaceholderData
      ? fullQuery
      : lightQuery;

  return {
    query,
    isLazyLoading: canLoadFullData && fullQuery.isPending,
  };
}
