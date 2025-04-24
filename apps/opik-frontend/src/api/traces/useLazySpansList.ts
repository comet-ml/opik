import { QueryConfig } from "@/api/api";
import useSpansList, {
  UseSpansListParams,
  UseSpansListResponse,
} from "@/api/traces/useSpansList";

export default function useLazySpansList(
  params: UseSpansListParams,
  options?: QueryConfig<UseSpansListResponse>,
) {
  const lightQuery = useSpansList(
    { ...params, excludeFields: ["input", "output"] },
    options,
  );

  const fullQuery = useSpansList(params, options);

  return {
    query: fullQuery.isPending ? lightQuery : fullQuery,
    isLazyLoading: fullQuery.isPending,
  };
}
