import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "@/api/api";

export const ANALYTICS_QUERIES_REST_ENDPOINT = "/v1/internal/analytics-queries";

// Placeholders an Ollie chart query uses for the dashboard date range. Mirrors ollie-assist's chart tool.
export const WINDOW_START_PLACEHOLDER = "{{window_start}}";
export const WINDOW_END_PLACEHOLDER = "{{window_end}}";

const ALL_TIME_START = "1970-01-01T00:00:00Z";

const toDateTimeLiteral = (iso: string) =>
  `parseDateTime64BestEffort('${iso.replace(/'/g, "")}', 9)`;

export const applyQueryWindow = (
  sql: string,
  intervalStart?: string,
  intervalEnd?: string,
) =>
  sql
    .split(WINDOW_START_PLACEHOLDER)
    .join(toDateTimeLiteral(intervalStart ?? ALL_TIME_START))
    .split(WINDOW_END_PLACEHOLDER)
    .join(toDateTimeLiteral(intervalEnd ?? new Date().toISOString()));

type UseAnalyticsQueryParams = {
  sql: string;
  projectId?: string | null;
  intervalStart?: string;
  intervalEnd?: string;
};

type AnalyticsQueryResponse = {
  results: Record<string, unknown>[];
};

const getAnalyticsQuery = async (
  { signal }: QueryFunctionContext,
  { sql, projectId, intervalStart, intervalEnd }: UseAnalyticsQueryParams,
) => {
  const { data } = await api.post<AnalyticsQueryResponse>(
    ANALYTICS_QUERIES_REST_ENDPOINT,
    {
      query: applyQueryWindow(sql, intervalStart, intervalEnd),
      project_id: projectId ?? null,
    },
    { signal },
  );

  return data.results ?? [];
};

export default function useAnalyticsQuery(
  params: UseAnalyticsQueryParams,
  options?: QueryConfig<Record<string, unknown>[]>,
) {
  return useQuery({
    queryKey: ["analytics-query", params],
    queryFn: (context) => getAnalyticsQuery(context, params),
    ...options,
  });
}
