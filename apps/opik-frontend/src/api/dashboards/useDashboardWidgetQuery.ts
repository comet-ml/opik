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
  // Not sent: the backend runs the query saved on the widget. Keying on it refetches after the widget is edited.
  sql: string;
};

type AnalyticsQueryResponse = {
  results: Record<string, unknown>[];
};

type ErrorBody = { message?: string; errors?: string[] };

// Retried, with react-query's backoff: a widget added or duplicated in this browser 404s until the next autosave,
// and a dashboard loading many widgets at once can hit the account's concurrent-query cap (429).
const RETRIES_BY_STATUS: Record<number, number> = { 404: 3, 429: 4 };

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
  options?: QueryConfig<Record<string, unknown>[]>,
) {
  return useQuery({
    queryKey: ["dashboard-widget-query", params],
    queryFn: (context) => getDashboardWidgetQuery(context, params),
    retry: (failureCount, error) => {
      const status =
        (error as { status?: number }).status ??
        (error as AxiosError).response?.status;
      return failureCount < (RETRIES_BY_STATUS[status ?? 0] ?? 0);
    },
    ...options,
  });
}
