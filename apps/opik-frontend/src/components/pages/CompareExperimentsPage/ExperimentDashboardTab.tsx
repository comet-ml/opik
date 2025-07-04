import React, { useEffect, useCallback } from "react";
import { useDashboardStore } from "./dashboardStore";
import { useDashboards } from "@/api/dashboards/useDashboards";
import { useExperimentDashboard } from "@/api/dashboards/useExperimentDashboard";
import { useDashboardById } from "@/api/dashboards/useDashboardById";
import DashboardSelector from "./DashboardSelector";
import DashboardSections from "./DashboardSections";

interface ExperimentDashboardTabProps {
  experimentId: string;
}

const ExperimentDashboardTab: React.FC<ExperimentDashboardTabProps> = ({ experimentId }) => {
  const { currentDashboardId, setCurrentDashboard, setCurrentExperiment } = useDashboardStore();
  
  // API queries with error handling and retry limits
  const { data: dashboardsData, isLoading: dashboardsLoading, error: dashboardsError } = useDashboards(
    {},
    {
      retry: 1,
      staleTime: 5 * 60 * 1000, // 5 minutes
      enabled: !!experimentId,
    }
  );
  
  const { data: experimentDashboard, isLoading: experimentDashboardLoading, error: experimentDashboardError } = useExperimentDashboard(
    { experimentId },
    { 
      enabled: !!experimentId,
      retry: 1,
      staleTime: 5 * 60 * 1000, // 5 minutes
    }
  );
  
  const { data: currentDashboard, isLoading: currentDashboardLoading, error: currentDashboardError } = useDashboardById(
    { dashboardId: currentDashboardId || "" },
    { 
      enabled: !!currentDashboardId,
      retry: 1,
      staleTime: 5 * 60 * 1000, // 5 minutes
    }
  );

  // Set current experiment when component mounts (only once)
  useEffect(() => {
    setCurrentExperiment(experimentId);
  }, [experimentId, setCurrentExperiment]);

  // Memoized callback to prevent unnecessary re-renders
  const handleDashboardSelection = useCallback(() => {
    // Only set dashboard if we don't already have one selected
    if (!currentDashboardId) {
      if (experimentDashboard?.dashboard_id) {
        setCurrentDashboard(experimentDashboard.dashboard_id);
      } else if (dashboardsData?.content && dashboardsData.content.length > 0) {
        // If no dashboard is associated with this experiment, use the first available dashboard
        setCurrentDashboard(dashboardsData.content[0].id);
      }
    }
  }, [currentDashboardId, experimentDashboard?.dashboard_id, dashboardsData?.content, setCurrentDashboard]);

  // Set current dashboard from experiment association (only when data changes)
  useEffect(() => {
    handleDashboardSelection();
  }, [handleDashboardSelection]);

  const isLoading = dashboardsLoading || experimentDashboardLoading || currentDashboardLoading;
  // Note: experimentDashboard being null is not an error - it just means no dashboard is associated
  const hasError = dashboardsError || experimentDashboardError || currentDashboardError;

  // Show error state
  if (hasError && !isLoading) {
    return (
      <div className="bg-background min-h-screen">
        <div className="text-center py-12 px-6">
          <div className="text-6xl mb-4">⚠️</div>
          <h2 className="comet-title-l mb-2">Error Loading Dashboard</h2>
          <p className="text-muted-foreground mb-6">
            There was an error loading the dashboard data. Please try refreshing the page.
          </p>
          <button 
            onClick={() => window.location.reload()} 
            className="btn btn-primary"
          >
            Refresh Page
          </button>
        </div>
      </div>
    );
  }

  if (isLoading) {
    return (
      <div className="bg-background min-h-screen">
        <div className="text-center py-12 px-6">
          <div className="text-2xl mb-4">⏳</div>
          <p className="text-muted-foreground">Loading dashboard...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="bg-background min-h-screen">
      {/* Dashboard Selector Header */}
      <DashboardSelector experimentId={experimentId} />

      {/* Dashboard Content */}
      <div>
        {!currentDashboard ? (
          <div className="text-center py-12 px-6">
            <div className="text-6xl mb-4">📊</div>
            <h2 className="comet-title-l mb-2">No Dashboard Selected</h2>
            <p className="text-muted-foreground mb-6">
              {!dashboardsData?.content?.length 
                ? "Create your first dashboard to get started with Python panels."
                : "Select a dashboard from the dropdown above or create a new one."
              }
            </p>
          </div>
        ) : currentDashboard.sections.length === 0 ? (
          <div className="text-center py-12 px-6">
            <div className="text-6xl mb-4">📊</div>
            <h2 className="comet-title-l mb-2">Empty Dashboard</h2>
            <p className="text-muted-foreground mb-6">
              This dashboard doesn't have any sections yet. Add your first section to get started.
            </p>
          </div>
        ) : (
          <DashboardSections experimentId={experimentId} dashboard={currentDashboard} />
        )}
      </div>
    </div>
  );
};

export default ExperimentDashboardTab; 