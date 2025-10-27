import React, { useMemo, useState } from "react";
import { useParams, useNavigate } from "@tanstack/react-router";
import { Plus, Settings2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import useAppStore from "@/store/AppStore";
import Loader from "@/components/shared/Loader/Loader";
import useDashboardById from "@/api/dashboards/useDashboardById";
import { formatDate } from "@/lib/date";
import { TimeInterval } from "@/types/dashboards";
import ChartCard from "./ChartCard";
import IntervalSelector from "./IntervalSelector";

const DashboardViewPage: React.FunctionComponent = () => {
  const navigate = useNavigate();
  const { dashboardId, projectId } = useParams({ strict: false }) as {
    dashboardId: string;
    projectId: string;
  };
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [selectedInterval, setSelectedInterval] = useState<TimeInterval>("DAILY");

  const { data: dashboard, isPending } = useDashboardById({
    dashboardId,
    workspaceName,
  });

  const sortedCharts = useMemo(() => {
    if (!dashboard?.charts) return [];
    return [...dashboard.charts].sort((a, b) => {
      const posA = a.position?.y ?? 0;
      const posB = b.position?.y ?? 0;
      return posA - posB;
    });
  }, [dashboard?.charts]);

  if (isPending) {
    return <Loader />;
  }

  if (!dashboard) {
    return (
      <div className="flex h-full items-center justify-center">
        <p className="comet-body-s text-light-slate">Dashboard not found</p>
      </div>
    );
  }

  return (
    <div className="h-full overflow-auto pb-6 pt-6">
      <div className="container mx-auto max-w-[1440px] px-6">
        {/* Header */}
        <div className="mb-6">
          <div className="mb-2 flex items-center justify-between">
            <div>
              <h1 className="comet-title-l mb-1">{dashboard.name}</h1>
              {dashboard.description && (
                <p className="comet-body-s text-light-slate">
                  {dashboard.description}
                </p>
              )}
            </div>
            <div className="flex items-center gap-2">
              <IntervalSelector
                value={selectedInterval}
                onChange={setSelectedInterval}
              />
              {dashboard.type === "custom" && (
                <Button
                  onClick={() => {
                    navigate({
                      to: "/$workspaceName/projects/$projectId/dashboards/$dashboardId/charts/new",
                      params: { workspaceName, projectId, dashboardId },
                    });
                  }}
                >
                  <Plus className="mr-2 size-4" />
                  Add chart
                </Button>
              )}
            </div>
          </div>

          <div className="flex items-center gap-4 text-sm text-light-slate">
            <span>Type: {dashboard.type === "prebuilt" ? "Prebuilt" : "Custom"}</span>
            {dashboard.created_at && (
              <span>Created: {formatDate(dashboard.created_at)}</span>
            )}
            {dashboard.last_updated_at && (
              <span>Updated: {formatDate(dashboard.last_updated_at)}</span>
            )}
          </div>
        </div>

        <Separator className="mb-6" />

        {/* Charts Grid */}
        {sortedCharts.length === 0 ? (
          <div className="flex h-64 items-center justify-center">
            <div className="text-center">
              <Settings2 className="mx-auto mb-4 size-12 text-light-slate" />
              <p className="comet-body-m mb-2">No charts yet</p>
              <p className="comet-body-s mb-4 text-light-slate">
                Add charts to visualize your project metrics
              </p>
              {dashboard.type === "custom" && (
                <Button
                  onClick={() => {
                    navigate({
                      to: "/$workspaceName/projects/$projectId/dashboards/$dashboardId/charts/new",
                      params: { workspaceName, projectId, dashboardId },
                    });
                  }}
                >
                  <Plus className="mr-2 size-4" />
                  Add your first chart
                </Button>
              )}
            </div>
          </div>
        ) : (
          <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
            {sortedCharts.map((chart) => (
              <ChartCard
                key={chart.id}
                chart={chart}
                dashboardId={dashboardId}
                interval={selectedInterval}
                onEditChart={(chartId) => {
                  navigate({
                    to: "/$workspaceName/projects/$projectId/dashboards/$dashboardId/charts/$chartId/edit",
                    params: { workspaceName, projectId, dashboardId, chartId },
                  });
                }}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default DashboardViewPage;

