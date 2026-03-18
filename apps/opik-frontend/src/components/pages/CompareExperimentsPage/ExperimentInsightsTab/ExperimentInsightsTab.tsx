import React, { useEffect } from "react";

import DashboardContent from "@/components/pages-shared/dashboards/DashboardContent/DashboardContent";
import ShareDashboardButton from "@/components/pages-shared/dashboards/ShareDashboardButton/ShareDashboardButton";
import { widgetResolver } from "@/components/pages-shared/dashboards/widgets/widgetRegistry";
import {
  useDashboardStore,
  selectSetRuntimeConfig,
  selectClearDashboard,
  selectSetReadOnly,
  selectSetWidgetResolver,
} from "@/store/DashboardStore";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import { EXPERIMENT_COMPARISON_TEMPLATE } from "@/lib/dashboard/templates";
import { DASHBOARD_TYPE } from "@/types/dashboard";
import CompareExperimentsButton from "@/components/pages/CompareExperimentsPage/CompareExperimentsButton/CompareExperimentsButton";
import { Separator } from "@/components/ui/separator";
import { cn } from "@/lib/utils";

interface ExperimentInsightsTabProps {
  experimentsIds: string[];
}

const ExperimentInsightsTab: React.FunctionComponent<
  ExperimentInsightsTabProps
> = ({ experimentsIds }) => {
  const loadDashboardFromBackend = useDashboardStore(
    (state) => state.loadDashboardFromBackend,
  );
  const clearDashboard = useDashboardStore(selectClearDashboard);
  const setWidgetResolver = useDashboardStore(selectSetWidgetResolver);
  const setReadOnly = useDashboardStore(selectSetReadOnly);
  const setRuntimeConfig = useDashboardStore(selectSetRuntimeConfig);

  useEffect(() => {
    loadDashboardFromBackend(EXPERIMENT_COMPARISON_TEMPLATE.config);
    setReadOnly(true);
    setWidgetResolver(widgetResolver);

    return () => {
      clearDashboard();
      setWidgetResolver(null);
    };
  }, [
    loadDashboardFromBackend,
    setReadOnly,
    setWidgetResolver,
    clearDashboard,
  ]);

  useEffect(() => {
    setRuntimeConfig({
      experimentIds: experimentsIds,
      dashboardType: DASHBOARD_TYPE.EXPERIMENTS,
    });

    return () => {
      setRuntimeConfig({});
    };
  }, [experimentsIds, setRuntimeConfig]);

  return (
    <>
      <PageBodyStickyContainer
        className="flex flex-wrap items-center justify-between gap-x-8 gap-y-2 pb-3 pt-2"
        direction="bidirectional"
        limitWidth
      >
        <div className="flex h-8 items-center gap-1.5 rounded-md border px-3">
          <EXPERIMENT_COMPARISON_TEMPLATE.icon
            className={cn(
              "size-3.5 shrink-0",
              EXPERIMENT_COMPARISON_TEMPLATE.iconColor,
            )}
          />
          <span className="comet-body-s-accented text-foreground">
            {EXPERIMENT_COMPARISON_TEMPLATE.name}
          </span>
        </div>

        <div className="flex shrink-0 items-center gap-2">
          <CompareExperimentsButton
            variant="outline"
            tooltipContent="Select experiments to compare"
          />
          <Separator orientation="vertical" className="mx-2 h-4" />
          <ShareDashboardButton />
        </div>
      </PageBodyStickyContainer>

      <div className="px-6 pb-4 pt-1">
        <DashboardContent />
      </div>
    </>
  );
};

export default ExperimentInsightsTab;
