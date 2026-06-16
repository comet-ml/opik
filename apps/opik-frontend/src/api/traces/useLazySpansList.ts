import { QueryConfig } from "@/api/api";
import useSpansList, {
  UseSpansListParams,
  UseSpansListResponse,
} from "@/api/traces/useSpansList";
import { Filters } from "@/types/filters";
import { COLUMN_CUSTOM_ID } from "@/types/shared";

const MAX_FULL_DATA_SPANS = 500;
const PAYLOAD_FIELDS = new Set(["input", "output"]);

type UseLazySpansListOptions = {
  loadFullData?: boolean;
};

const isPayloadField = (field?: string) =>
  field !== undefined && PAYLOAD_FIELDS.has(field);

const isPayloadKey = (key?: string) =>
  key !== undefined &&
  (PAYLOAD_FIELDS.has(key) ||
    key.startsWith("input.") ||
    key.startsWith("input[") ||
    key.startsWith("output.") ||
    key.startsWith("output["));

export const shouldLoadFullSpansData = (search?: unknown, filters?: Filters) =>
  (typeof search === "string" && search.trim().length > 0) ||
  Boolean(
    filters?.some(
      (filter) =>
        isPayloadField(filter.field) ||
        (filter.field === COLUMN_CUSTOM_ID && isPayloadKey(filter.key)),
    ),
  );

export default function useLazySpansList(
  params: UseSpansListParams,
  options?: QueryConfig<UseSpansListResponse>,
  lazyOptions?: UseLazySpansListOptions,
) {
  const loadFullData = lazyOptions?.loadFullData ?? false;
  const enabled = options?.enabled ?? true;
  const lightQuery = useSpansList(
    { ...params, exclude: ["input", "output"] },
    {
      ...options,
      enabled: !loadFullData && enabled,
    },
  );

  const canLoadFullData =
    loadFullData ||
    (!lightQuery.isPlaceholderData &&
      lightQuery.data?.total !== undefined &&
      lightQuery.data.total <= MAX_FULL_DATA_SPANS);

  const fullQuery = useSpansList(params, {
    ...options,
    enabled: canLoadFullData && enabled,
  });

  const query =
    loadFullData ||
    (canLoadFullData && fullQuery.data && !fullQuery.isPlaceholderData)
      ? fullQuery
      : lightQuery;

  return {
    query,
    isLazyLoading: !loadFullData && canLoadFullData && fullQuery.isPending,
  };
}
