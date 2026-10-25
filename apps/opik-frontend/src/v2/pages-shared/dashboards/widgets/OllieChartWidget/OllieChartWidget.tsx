import React, { memo, useEffect, useMemo, useRef, useState } from "react";
import { v4 as uuidv4 } from "uuid";
import { useShallow } from "zustand/react/shallow";
import { RefreshCw } from "lucide-react";
import OllieOwl from "@/icons/ollie-owl.svg?react";

import DashboardWidget from "@/shared/Dashboard/DashboardWidget/DashboardWidget";
import VegaChart from "@/shared/VegaChart/VegaChart";
import ChartSkeleton from "@/shared/VegaChart/ChartSkeleton";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";
import OllieChartActionsMenu from "./OllieChartActionsMenu";
import OllieKpi, { parseKpiSpec } from "./OllieKpi";
import {
  useDashboardStore,
  selectRuntimeConfig,
  selectReadOnly,
  selectSavedWidgetIds,
} from "@/store/DashboardStore";
import {
  DASHBOARD_SCOPE,
  DASHBOARD_TYPE,
  DashboardWidgetComponentProps,
  OllieChartWidgetType,
} from "@/types/dashboard";
import { calculateIntervalConfig } from "@/v2/pages-shared/traces/MetricDateRangeSelect/utils";
import { DEFAULT_DATE_PRESET } from "@/v2/pages-shared/traces/MetricDateRangeSelect/constants";
import useDashboardWidgetQuery from "@/api/dashboards/useDashboardWidgetQuery";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";

const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;

// The statuses the widget query endpoint uses on purpose; anything else shows the backend's message.
const QUERY_ERRORS: Record<number, { title: string; message: string }> = {
  403: {
    title: "Data is masked",
    message:
      "Charts from custom queries can't be shown while your data is masked",
  },
  404: {
    title: "Query not found",
    message:
      "The saved query for this chart no longer exists. Ask Ollie to create it again",
  },
  501: {
    title: "Charts unavailable",
    message: "Ollie charts are turned off for this workspace",
  },
};

type QueryError = Error & { status?: number; response?: { status?: number } };

const queryErrorStatus = (error: QueryError) =>
  error.status ?? error.response?.status;

const describeQueryError = (error: QueryError) =>
  QUERY_ERRORS[queryErrorStatus(error) ?? 0] ?? {
    title: "Query failed",
    message: error.message,
  };

const formatComputedAgo = (timestamp: number, now: number) => {
  const minutes = Math.floor((now - timestamp) / MINUTE);
  if (minutes < 1) return "computed just now";
  if (minutes < 60) return `computed ${minutes}m ago`;
  return `computed ${Math.floor(minutes / 60)}h ago`;
};

const ComputedAgo: React.FC<{ timestamp: number }> = ({ timestamp }) => {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(id);
  }, []);
  return (
    <span className="comet-body-xs whitespace-nowrap text-light-slate">
      {formatComputedAgo(timestamp, Math.max(now, timestamp))}
    </span>
  );
};

const OllieBadge = () => (
  <TooltipWrapper content="Created with Ollie">
    <span className="flex size-4 shrink-0 items-center justify-center rounded bg-[color-mix(in_srgb,var(--color-ollie)_12%,transparent)]">
      <OllieOwl className="size-3 text-[var(--color-ollie)]" />
    </span>
  </TooltipWrapper>
);

const OllieChartWidget: React.FunctionComponent<
  DashboardWidgetComponentProps
> = ({ sectionId, widgetId, preview = false }) => {
  const readOnly = useDashboardStore(selectReadOnly);
  const ollieEnabled = useIsFeatureEnabled(FeatureToggleKeys.OLLIE_ENABLED);
  const dateRange = useDashboardStore(
    (state) => selectRuntimeConfig(state)?.dateRange ?? DEFAULT_DATE_PRESET,
  );
  // Experiments dashboards show no date range, so a widget there must not be bounded by a hidden one.
  const hasDateRange = useDashboardStore(
    (state) =>
      selectRuntimeConfig(state)?.dashboardType !== DASHBOARD_TYPE.EXPERIMENTS,
  );
  const { dashboardId, dashboardScope } = useDashboardStore(
    useShallow((state) => ({
      dashboardId: selectRuntimeConfig(state)?.dashboardId,
      dashboardScope: selectRuntimeConfig(state)?.dashboardScope,
    })),
  );
  const widget = useDashboardStore(
    useShallow((state) => {
      if (preview) {
        return state.previewWidget;
      }
      if (!sectionId || !widgetId) return null;
      const section = state.sections.find((s) => s.id === sectionId);
      return section?.widgets.find((w) => w.id === widgetId);
    }),
  );

  // Editing a widget previews it under its saved id, so the preview can run the same query; a new one has no id yet.
  const savedPreviewId = useDashboardStore((state) => {
    const id = preview ? state.previewWidget?.id : undefined;
    return id && state.sections.some((s) => s.widgets.some((w) => w.id === id))
      ? id
      : undefined;
  });
  const queryWidgetId = preview ? savedPreviewId : widgetId;
  // A 404 is worth retrying only until the next autosave stores the widget; once saved, the query is really gone.
  const savedWidgetIds = useDashboardStore(selectSavedWidgetIds);
  const retryNotFound =
    !savedWidgetIds ||
    !queryWidgetId ||
    !savedWidgetIds.includes(queryWidgetId);

  const config = (widget?.config ?? {}) as OllieChartWidgetType["config"];
  const { spec, query, rows: snapshotRows } = config;
  const kpi = parseKpiSpec(spec);

  const { intervalStart, intervalEnd } = useMemo(
    () =>
      hasDateRange
        ? calculateIntervalConfig(dateRange)
        : { intervalStart: undefined, intervalEnd: undefined },
    [dateRange, hasDateRange],
  );

  // A KPI on a date range compares with the window of the same length just before it. A preset ("Past 30 days") has
  // no end: it runs up to now.
  const previousWindow = useMemo(() => {
    if (!kpi?.window_days || !intervalStart) return null;
    const start = new Date(intervalStart).getTime();
    const end = intervalEnd ? new Date(intervalEnd).getTime() : Date.now();
    const length = end - start;
    if (!Number.isFinite(length) || length <= 0) return null;
    // A range starting at midnight counts today's part as a day ("Past 30 days" is 29 days and today); one starting
    // on the hour ("Past day") is whole hours.
    const days =
      start % DAY === 0 ? Math.ceil(length / DAY) : Math.round(length / DAY);
    return {
      intervalStart: new Date(start - length).toISOString(),
      intervalEnd: intervalStart,
      days: Math.max(1, days),
    };
  }, [kpi?.window_days, intervalStart, intervalEnd]);

  // `live` also covers the moment before the dashboard id arrives (skeleton); only `canQuery` may hit the endpoint.
  const canQuery = Boolean(query?.id && dashboardId && queryWidgetId);
  const { data: previousRows } = useDashboardWidgetQuery(
    {
      dashboardId: dashboardId ?? "",
      scope: dashboardScope ?? DASHBOARD_SCOPE.WORKSPACE,
      widgetId: queryWidgetId ?? "",
      queryId: query?.id ?? "",
      intervalStart: previousWindow?.intervalStart,
      intervalEnd: previousWindow?.intervalEnd,
    },
    { enabled: canQuery && Boolean(previousWindow), retryNotFound: false },
  );
  const {
    data: rows,
    isPending,
    isFetching,
    error,
    refetch,
    dataUpdatedAt,
  } = useDashboardWidgetQuery(
    {
      dashboardId: dashboardId ?? "",
      scope: dashboardScope ?? DASHBOARD_SCOPE.WORKSPACE,
      widgetId: queryWidgetId ?? "",
      queryId: query?.id ?? "",
      intervalStart,
      intervalEnd,
    },
    // Only an unsaved widget falls back to the rows the chart was made with.
    { enabled: canQuery, retryNotFound },
  );
  const live = Boolean(query?.id && queryWidgetId);

  // An autosave slower than the 404 retries saves the widget after its query already gave up; run it once more then.
  const wasSavedRef = useRef(!retryNotFound);
  useEffect(() => {
    const saved = !retryNotFound;
    if (
      saved &&
      !wasSavedRef.current &&
      error &&
      queryErrorStatus(error) === 404
    ) {
      refetch();
    }
    wasSavedRef.current = saved;
  }, [retryNotFound, error, refetch]);

  // Once per failed load: the same error object survives re-renders. An unsaved widget's 404 is expected and stays
  // unreported after the save that ends it; masked data and charts turned off are states the widget explains.
  const seenErrorRef = useRef<QueryError | null>(null);
  useEffect(() => {
    if (preview || !error || seenErrorRef.current === error) return;
    seenErrorRef.current = error;
    const status = queryErrorStatus(error);
    if ((status === 404 && retryNotFound) || status === 403 || status === 501)
      return;
    trackEvent(OpikEvent.DASHBOARD_OLLIE_WIDGET_LOAD_FAILED, {
      dashboard_id: dashboardId,
      dashboard_scope: dashboardScope,
      widget_id: widgetId,
      query_id: query?.id,
      status: status ?? null,
    });
  }, [
    preview,
    error,
    retryNotFound,
    dashboardId,
    dashboardScope,
    widgetId,
    query?.id,
  ]);

  if (!widget) {
    return null;
  }

  const title = widget.title || widget.generatedTitle || "Ollie chart";

  // Editing goes through Ollie when it is on; without it the JSON editor stays as the fallback. The bridge is read
  // at click time: the sidebar attaches it after this widget first renders.
  const editInOllie =
    ollieEnabled && spec && query?.id
      ? () => {
          const entryId = uuidv4();
          trackEvent(OpikEvent.DASHBOARD_EDIT_IN_OLLIE_CLICKED, {
            entry_id: entryId,
            dashboard_id: dashboardId,
            dashboard_scope: dashboardScope,
            widget_id: widget.id,
            query_id: query.id,
          });
          window.opikBridge?.editChart?.({
            widgetId: widget.id,
            title,
            description: config.description,
            spec,
            query: { id: query.id },
            rows: live ? rows : snapshotRows,
            entryId,
          });
        }
      : undefined;

  // A copy refined with Ollie and added as a new widget; this one stays as it is.
  const duplicateInOllie =
    ollieEnabled && spec && query?.id
      ? () => {
          window.opikBridge?.editChart?.({
            widgetId: widget.id,
            title,
            description: config.description,
            spec,
            query: { id: query.id },
            rows: live ? rows : snapshotRows,
            entryId: uuidv4(),
            duplicate: true,
          });
        }
      : undefined;

  const renderContent = () => {
    if (!spec) {
      return (
        <DashboardWidget.EmptyState
          title="No chart"
          message="Ask Ollie to create a chart and add it to this dashboard"
        />
      );
    }

    if (live && isPending) {
      return <ChartSkeleton className="size-full" />;
    }

    if (live && error) {
      return <DashboardWidget.EmptyState {...describeQueryError(error)} />;
    }

    const chartRows = live ? rows : snapshotRows;
    if (!chartRows?.length) {
      return (
        <DashboardWidget.EmptyState
          title="No data"
          message="No data for the selected period"
        />
      );
    }

    if (kpi) {
      return (
        <OllieKpi
          kpi={kpi}
          rows={chartRows}
          previousRows={live && previousWindow ? previousRows : null}
          comparedWith={
            previousWindow ? `previous ${previousWindow.days} days` : undefined
          }
        />
      );
    }

    return (
      <div className="size-full p-2">
        <VegaChart spec={spec} rows={chartRows} />
      </div>
    );
  };

  return (
    <DashboardWidget>
      {preview ? (
        <DashboardWidget.PreviewHeader />
      ) : (
        <DashboardWidget.Header
          title={widget.title || widget.generatedTitle || ""}
          // Ollie writes the chart's scope as its description; an editor's own subtitle wins.
          subtitle={widget.subtitle || config.description || undefined}
          readOnly={readOnly}
          titleAdornment={<OllieBadge />}
          meta={
            canQuery && dataUpdatedAt ? (
              <>
                <ComputedAgo timestamp={dataUpdatedAt} />
                <TooltipWrapper content="Refresh">
                  <button
                    type="button"
                    className="flex size-4 items-center justify-center text-light-slate hover:text-foreground"
                    onClick={() => refetch()}
                  >
                    <RefreshCw
                      className={cn("size-3", isFetching && "animate-spin")}
                    />
                  </button>
                </TooltipWrapper>
              </>
            ) : undefined
          }
          actions={
            <OllieChartActionsMenu
              sectionId={sectionId!}
              widgetId={widgetId!}
              widgetTitle={title}
              onEditInOllie={editInOllie}
              onDuplicateInOllie={duplicateInOllie}
              onRefresh={canQuery ? () => refetch() : undefined}
            />
          }
          dragHandle={<DashboardWidget.DragHandle />}
        />
      )}
      <DashboardWidget.Content>{renderContent()}</DashboardWidget.Content>
    </DashboardWidget>
  );
};

const arePropsEqual = (
  prev: DashboardWidgetComponentProps,
  next: DashboardWidgetComponentProps,
) => {
  if (prev.preview !== next.preview) return false;
  if (prev.preview && next.preview) return true;
  return prev.sectionId === next.sectionId && prev.widgetId === next.widgetId;
};

export default memo(OllieChartWidget, arePropsEqual);
