import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import { AxiosError } from "axios";
import api, {
  DASHBOARDS_REST_ENDPOINT,
  INSIGHTS_VIEWS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { DASHBOARD_SCOPE } from "@/types/dashboard";

type UseDashboardWidgetQueryParams = {
  dashboardId: string;
  scope: DASHBOARD_SCOPE;
  widgetId: string;
  intervalStart?: string;
  intervalEnd?: string;
  queryId: string;
};

type AnalyticsQueryResponse = {
  results: Record<string, unknown>[];
};

type ErrorBody = { message?: string; errors?: string[] };

// Retried: a widget added or duplicated in this browser 404s until the next autosave (only retried while the widget
// isn't saved), and a dashboard loading more widgets than the workspace's concurrent-query cap gets 429 until earlier
// widgets finish. A 429 is retried for about a minute, at most 5s apart and jittered so waiting widgets spread out.
const RETRIES_BY_STATUS: Record<number, number> = { 404: 3, 429: 12 };
const MAX_429_RETRY_DELAY_MS = 5000;

const statusOf = (error: unknown) =>
  (error as { status?: number }).status ??
  (error as AxiosError).response?.status;

const retryDelay = (attempt: number, error: unknown) =>
  statusOf(error) === 429
    ? Math.min(1000 * 2 ** attempt, MAX_429_RETRY_DELAY_MS) +
      Math.random() * 1000
    : Math.min(1000 * 2 ** attempt, 30000);

const getDashboardWidgetQuery = async (
  { signal }: QueryFunctionContext,
  {
    dashboardId,
    scope,
    widgetId,
    intervalStart,
    intervalEnd,
  }: UseDashboardWidgetQueryParams,
) => {
  const base =
    scope === DASHBOARD_SCOPE.INSIGHTS
      ? INSIGHTS_VIEWS_REST_ENDPOINT
      : DASHBOARDS_REST_ENDPOINT;

  try {
    const { data } = await api.post<AnalyticsQueryResponse>(
      `${base}${dashboardId}/widgets/${widgetId}/query`,
      {
        interval_start: intervalStart ?? null,
        interval_end: intervalEnd ?? null,
      },
      { signal },
    );
    return data.results ?? [];
  } catch (error) {
    const body = (error as AxiosError<ErrorBody>).response?.data;
    const message = body?.errors?.join("; ") || body?.message;
    if (message) {
      throw Object.assign(new Error(message), {
        status: (error as AxiosError).response?.status,
      });
    }
    throw error;
  }
};

export default function useDashboardWidgetQuery(
  params: UseDashboardWidgetQueryParams,
  {
    retryNotFound = true,
    ...options
  }: QueryConfig<Record<string, unknown>[]> & { retryNotFound?: boolean } = {},
) {
  return useQuery({
    queryKey: ["dashboard-widget-query", params],
    queryFn: (context) => getDashboardWidgetQuery(context, params),
    retry: (failureCount, error) => {
      const status = statusOf(error);
      if (status === 404 && !retryNotFound) return false;
      return failureCount < (RETRIES_BY_STATUS[status ?? 0] ?? 0);
    },
    retryDelay,
    ...options,
  });
}
