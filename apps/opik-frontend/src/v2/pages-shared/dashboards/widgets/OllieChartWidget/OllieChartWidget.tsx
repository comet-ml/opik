import React, { memo, useEffect, useMemo, useState } from "react";
import { useShallow } from "zustand/react/shallow";
import { RefreshCw } from "lucide-react";
import OllieOwl from "@/icons/ollie-owl.svg?react";

import DashboardWidget from "@/shared/Dashboard/DashboardWidget/DashboardWidget";
import VegaChart from "@/shared/VegaChart/VegaChart";
import ChartSkeleton from "@/shared/VegaChart/ChartSkeleton";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";
import OllieChartActionsMenu from "./OllieChartActionsMenu";
import {
  useDashboardStore,
  selectRuntimeConfig,
  selectReadOnly,
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

const MINUTE = 60_000;

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

  const config = (widget?.config ?? {}) as OllieChartWidgetType["config"];
  const { spec, query, rows: snapshotRows } = config;

  const { intervalStart, intervalEnd } = useMemo(
    () =>
      hasDateRange
        ? calculateIntervalConfig(dateRange)
        : { intervalStart: undefined, intervalEnd: undefined },
    [dateRange, hasDateRange],
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
      widgetId: widgetId ?? "",
      sql: query?.sql ?? "",
      intervalStart,
      intervalEnd,
    },
    // A preview has no saved widget to run, so it shows the rows the chart was made with.
    { enabled: Boolean(query?.sql && dashboardId && widgetId && !preview) },
  );
  const live = Boolean(query?.sql && !preview);

  if (!widget) {
    return null;
  }

  // Editing goes through Ollie when it is on; without it the JSON editor stays as the fallback. The bridge is read
  // at click time: the sidebar attaches it after this widget first renders.
  const editInOllie =
    ollieEnabled && spec && query?.sql
      ? () =>
          window.opikBridge?.editChart?.({
            widgetId: widget.id,
            title: widget.title || widget.generatedTitle || "Ollie chart",
            description: config.description,
            spec,
            query: { sql: query.sql, projectId: query.projectId },
            rows: live ? rows : snapshotRows,
          })
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
      return (
        <DashboardWidget.EmptyState
          title="Query failed"
          message={error.message}
        />
      );
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
          subtitle={widget.subtitle}
          readOnly={readOnly}
          titleAdornment={<OllieBadge />}
          meta={
            query?.sql && dataUpdatedAt ? (
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
              widgetTitle={widget.title}
              onEditInOllie={editInOllie}
              onRefresh={() => refetch()}
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
