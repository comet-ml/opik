import { QueryConfig } from "@/api/api";
import useSpansList, {
  UseSpansListParams,
  UseSpansListResponse,
} from "@/api/traces/useSpansList";

type UseLazySpansListConfig = {
  loadFullData?: boolean;
  maxFullDataSpans?: number;
};

const DEFAULT_MAX_FULL_DATA_SPANS = 500;

export default function useLazySpansList(
  params: UseSpansListParams,
  options?: QueryConfig<UseSpansListResponse>,
  config: UseLazySpansListConfig = {},
) {
  const {
    loadFullData = false,
    maxFullDataSpans = DEFAULT_MAX_FULL_DATA_SPANS,
  } = config;
  const lightQuery = useSpansList(
    { ...params, exclude: ["input", "output"] },
    options,
  );

  const canLoadFullData =
    !lightQuery.isPlaceholderData &&
    lightQuery.data?.total !== undefined &&
    (loadFullData || lightQuery.data.total <= maxFullDataSpans);

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
