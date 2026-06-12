import { QueryConfig } from "@/api/api";
import useSpansList, {
  UseSpansListParams,
  UseSpansListResponse,
} from "@/api/traces/useSpansList";

type UseLazySpansListConfig = {
  loadFullData?: boolean;
  maxFullDataSpans?: number;
};

export default function useLazySpansList(
  params: UseSpansListParams,
  options?: QueryConfig<UseSpansListResponse>,
  config: UseLazySpansListConfig = {},
) {
  const lightQuery = useSpansList(
    { ...params, exclude: ["input", "output"] },
    options,
  );

  const canLoadFullData =
    !lightQuery.isPlaceholderData &&
    lightQuery.data?.total !== undefined &&
    (config.loadFullData ||
      config.maxFullDataSpans === undefined ||
      lightQuery.data.total <= config.maxFullDataSpans);

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
