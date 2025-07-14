import React, { useEffect, useCallback } from "react";
import { useDashboardStore } from "./dashboardStore";
import useDashboards from "@/api/dashboards/useDashboards";
import { useExperimentDashboard } from "@/api/dashboards/useExperimentDashboard";
import useDashboardById from "@/api/dashboards/useDashboardById";
import DashboardSelector from "./DashboardSelector";
import DashboardSections from "./DashboardSections";
import { Button } from "@/components/ui/button";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import Loader from "@/components/shared/Loader/Loader";
import { RotateCw, AlertCircle, Layers3, PlusCircle } from "lucide-react";
import NoData from "@/components/shared/NoData/NoData";
import ExplainerCallout from "@/components/shared/ExplainerCallout/ExplainerCallout";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

interface ExperimentDashboardTabProps {
  experimentId: string;
}

const ExperimentDashboardTab: React.FC<ExperimentDashboardTabProps> = ({ experimentId }) => {
  const { currentDashboardId, setCurrentDashboard } = useDashboardStore();
  
  // API queries with proper error handling and retry configuration
  const { 
    data: dashboardsData, 
    isLoading: dashboardsLoading, 
    error: dashboardsError,
    refetch: refetchDashboards 
  } = useDashboards(
    {},
    {
      retry: 1,
      staleTime: 5 * 60 * 1000, // 5 minutes
      enabled: !!experimentId,
    }
  );
  
  const { 
    data: experimentDashboard, 
    isLoading: experimentDashboardLoading, 
    error: experimentDashboardError,
    refetch: refetchExperimentDashboard 
  } = useExperimentDashboard(
    { experimentId },
    { 
      enabled: !!experimentId,
      retry: 1,
      staleTime: 5 * 60 * 1000, // 5 minutes
    }
  );
  
  const { 
    data: currentDashboard, 
    isLoading: currentDashboardLoading, 
    error: currentDashboardError,
    refetch: refetchCurrentDashboard 
  } = useDashboardById(
    { dashboardId: currentDashboardId || "" },
    { 
      enabled: !!currentDashboardId,
      retry: 1,
      staleTime: 5 * 60 * 1000, // 5 minutes
    }
  );

  // No need to manage experiment ID in store - it's passed as prop

  // Auto-select dashboard logic
  const handleDashboardSelection = useCallback(() => {
    if (!currentDashboardId) {
      if (experimentDashboard?.dashboard_id) {
        setCurrentDashboard(experimentDashboard.dashboard_id);
      } else if (dashboardsData && dashboardsData.length > 0) {
        setCurrentDashboard(dashboardsData[0].id);
      }
    }
  }, [currentDashboardId, experimentDashboard?.dashboard_id, dashboardsData, setCurrentDashboard]);

  useEffect(() => {
    handleDashboardSelection();
  }, [handleDashboardSelection]);

  const isLoading = dashboardsLoading || experimentDashboardLoading || currentDashboardLoading;
  const hasError = dashboardsError || experimentDashboardError || currentDashboardError;

  const handleRefresh = () => {
    refetchDashboards();
    refetchExperimentDashboard();
    if (currentDashboardId) {
      refetchCurrentDashboard();
    }
  };

  // Loading state
  if (isLoading) {
    return (
      <PageBodyScrollContainer>
        <div className="flex h-[60vh] items-center justify-center">
          <Loader />
        </div>
      </PageBodyScrollContainer>
    );
  }

  // Error state
  if (hasError && !isLoading) {
    return (
      <PageBodyScrollContainer>
        <div className="flex h-[60vh] items-center justify-center">
          <div className="text-center px-6 py-12">
            <AlertCircle className="mx-auto mb-4 size-12 text-destructive" />
            <h2 className="comet-title-l mb-2">Error Loading Dashboard</h2>
            <p className="comet-body-s text-muted-foreground mb-6 max-w-md">
              There was an error loading the dashboard data. Please try refreshing to reload the content.
            </p>
            <Button 
              onClick={handleRefresh}
              variant="outline"
              size="sm"
            >
              <RotateCw className="mr-2 size-4" />
              Refresh
            </Button>
          </div>
        </div>
      </PageBodyScrollContainer>
    );
  }

  return (
    <PageBodyScrollContainer>
      {/* Explainer Callout can be added here when appropriate explainer is available */}
      
      {/* Header with Dashboard Selector */}
      <PageBodyStickyContainer className="-mt-4 pb-4 pt-6" direction="horizontal" limitWidth>
        <div className="flex items-center justify-between">
          <h2 className="comet-title-l">Experiment Dashboard</h2>
          <Button
            onClick={handleRefresh}
            variant="outline"
            size="icon-sm"
            className="shrink-0"
          >
            <RotateCw className="size-4" />
          </Button>
        </div>
        <div className="mt-4">
          <DashboardSelector experimentId={experimentId} />
        </div>
      </PageBodyStickyContainer>

      {/* Dashboard Content */}
      <PageBodyStickyContainer className="pb-6" direction="horizontal" limitWidth>
        {!currentDashboard ? (
          <NoData
            icon={<Layers3 className="size-16 text-muted-slate" />}
            title="No Dashboard Selected"
            message={!dashboardsData?.length 
              ? "Create your first dashboard to get started with Python panels and visualizations."
              : "Select a dashboard from the dropdown above to view its contents, or create a new one."
            }
            className="rounded-lg border bg-card"
          />
        ) : (
          <DashboardSections 
            experimentId={experimentId}
            contextExperimentId={experimentId}
            dashboard={currentDashboard}
          />
        )}
      </PageBodyStickyContainer>
    </PageBodyScrollContainer>
  );
};

export default ExperimentDashboardTab; 
