import React, { useCallback, useEffect, useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { useActiveProjectId } from "@/store/AppStore";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import Loader from "@/shared/Loader/Loader";
import SearchInput from "@/shared/SearchInput/SearchInput";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import OptimizationProgressChartContainer from "@/v2/pages-shared/experiments/OptimizationProgressChart";
import BestTrialPrompt from "./BestTrialPrompt";
import { IN_PROGRESS_OPTIMIZATION_STATUSES } from "@/lib/optimizations";
import { formatDate } from "@/lib/date";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { useOptimizationData } from "./useOptimizationData";
import { useOptimizationColumns } from "./useOptimizationColumns";
import OptimizationHeader from "./OptimizationHeader";
import OptimizationTrialsControls from "./OptimizationTrialsControls";
import OptimizationTrialsTable from "./OptimizationTrialsTable";
import OptimizationKPICards from "./OptimizationKPICards";
import RunErrorPanel from "./RunErrorPanel";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { usePermissions } from "@/contexts/PermissionsContext";

enum OPTIMIZATION_TAB {
  OVERVIEW = "overview",
  TRIALS = "trials",
}

const OptimizationPage: React.FC = () => {
  const navigate = useNavigate();
  const activeProjectId = useActiveProjectId();
  const {
    permissions: { canUseOptimizationStudio },
  } = usePermissions();

  const {
    workspaceName,
    optimizationId,
    optimization,
    experiments,
    candidates,
    rows,
    noDataText,
    sortableBy,
    search,
    setSearch,
    total,
    page,
    setPage,
    pageSize,
    setPageSize,

    bestCandidate,
    baselineCandidate,
    baselineExperiment,
    inProgressInfo,
    isRunningMiniBatches,
    isTestSuite,
    isOptimizationPending,
    isExperimentsPending,
    isExperimentsPlaceholderData,
    isExperimentsFetching,

    sortedColumns,
    setSortedColumns,
    selectedColumns,
    setSelectedColumns,
    columnsOrder,
    setColumnsOrder,
    columnsWidth,
    setColumnsWidth,
    height,
    setHeight,

    handleRowClick,
    handleRefresh,
  } = useOptimizationData();

  const [selectedTrialId, setSelectedTrialId] = useState<string | undefined>();
  const [activeTab, setActiveTab] = useState<string>(OPTIMIZATION_TAB.OVERVIEW);
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  useEffect(() => {
    if (optimization?.dataset_name) {
      const label = optimization.created_at
        ? `${optimization.dataset_name} - ${formatDate(
            optimization.created_at,
          )}`
        : optimization.dataset_name;
      setBreadcrumbParam("optimizationId", optimizationId, label);
    }
  }, [
    optimizationId,
    optimization?.dataset_name,
    optimization?.created_at,
    setBreadcrumbParam,
  ]);

  const handleTrialClick = useCallback(
    (candidateId: string) => {
      const candidate = candidates.find((c) => c.candidateId === candidateId);
      if (!candidate) return;
      navigate({
        to: "/$workspaceName/projects/$projectId/optimizations/$optimizationId/trials",
        params: {
          optimizationId,
          workspaceName,
          projectId: activeProjectId!,
        },
        search: {
          trials: candidate.experimentIds,
          trialNumber: candidate.trialNumber,
        },
      });
    },
    [candidates, navigate, optimizationId, workspaceName, activeProjectId],
  );

  const isInProgress =
    !!optimization?.status &&
    IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization.status);

  const { columnsDef, columns } = useOptimizationColumns({
    candidates,
    experiments,
    baselineExperiment,
    columnsOrder,
    selectedColumns,
    sortableBy,
    bestCandidateId: bestCandidate?.candidateId,
    baselineCandidate,
    isTestSuite,
    isInProgress,
    inProgressInfo,
    objectiveName: optimization?.objective_name,
  });

  if (isOptimizationPending || isExperimentsPending) {
    return <Loader />;
  }

  const canRerun =
    canUseOptimizationStudio &&
    Boolean(optimization?.studio_config) &&
    Boolean(optimization?.id) &&
    optimization?.status &&
    !IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization.status);

  return (
    <div className="flex min-h-full flex-col pt-4">
      <div className="shrink-0 pb-4">
        <OptimizationHeader
          optimization={optimization}
          status={optimization?.status}
          optimizationId={optimization?.id}
          isStudioOptimization={Boolean(optimization?.studio_config)}
          canRerun={canRerun}
        />
      </div>

      <Tabs
        value={activeTab}
        onValueChange={setActiveTab}
        className="flex flex-col"
      >
        <TabsList variant="segmented-primary" className="shrink-0">
          <TabsTrigger
            variant="segmented-primary"
            size="sm"
            value={OPTIMIZATION_TAB.OVERVIEW}
          >
            Overview
          </TabsTrigger>
          <TabsTrigger
            variant="segmented-primary"
            size="sm"
            value={OPTIMIZATION_TAB.TRIALS}
          >
            Trials
          </TabsTrigger>
        </TabsList>

        <TabsContent value={OPTIMIZATION_TAB.OVERVIEW} className="mt-0 pt-4">
          {optimization &&
            optimization.status === OPTIMIZATION_STATUS.ERROR && (
              <div className="shrink-0 pb-4">
                <RunErrorPanel optimization={optimization} />
              </div>
            )}
          <div className="shrink-0 pb-4">
            <OptimizationKPICards
              experiments={experiments}
              baselineCandidate={baselineCandidate}
              bestCandidate={bestCandidate}
              isTestSuite={isTestSuite}
              objectiveName={optimization?.objective_name}
              optimizationCreatedAt={optimization?.created_at}
              optimizationLastUpdatedAt={optimization?.last_updated_at}
              isInProgress={
                !!optimization?.status &&
                IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization.status)
              }
            />
          </div>

          <div className="shrink-0 pb-4">
            <OptimizationProgressChartContainer
              candidates={candidates}
              bestCandidateId={bestCandidate?.candidateId}
              objectiveName={optimization?.objective_name}
              status={optimization?.status}
              onTrialClick={handleTrialClick}
              isTestSuite={isTestSuite}
              inProgressInfo={inProgressInfo}
              isRunningMiniBatches={isRunningMiniBatches}
              selectedTrialId={selectedTrialId}
              onTrialSelect={setSelectedTrialId}
            />
          </div>

          {bestCandidate && (
            <div className="shrink-0 pb-4">
              <BestTrialPrompt
                bestCandidate={bestCandidate}
                candidates={candidates}
                experiments={experiments}
                onViewTrial={() => handleTrialClick(bestCandidate.candidateId)}
              />
            </div>
          )}
        </TabsContent>

        <TabsContent value={OPTIMIZATION_TAB.TRIALS} className="mt-0 pt-4">
          <div className="flex flex-wrap items-center justify-between gap-x-8 gap-y-2 pb-4">
            <SearchInput
              searchText={search ?? ""}
              setSearchText={setSearch}
              placeholder="Search by trial name"
              className="w-[320px]"
              dimension="sm"
            />
            <OptimizationTrialsControls
              onRefresh={handleRefresh}
              isFetching={isExperimentsFetching}
              rowHeight={height}
              onRowHeightChange={setHeight}
              columnsDef={columnsDef}
              selectedColumns={selectedColumns}
              onSelectedColumnsChange={setSelectedColumns}
              columnsOrder={columnsOrder}
              onColumnsOrderChange={setColumnsOrder}
            />
          </div>

          <div className="pb-6">
            <OptimizationTrialsTable
              columns={columns}
              rows={rows}
              onRowClick={handleRowClick}
              rowHeight={height}
              noDataText={noDataText}
              sortedColumns={sortedColumns}
              onSortChange={setSortedColumns}
              columnsWidth={columnsWidth}
              onColumnsWidthChange={setColumnsWidth}
              highlightedTrialId={selectedTrialId}
              showLoadingOverlay={
                isExperimentsPlaceholderData && isExperimentsFetching
              }
            />
            <div className="py-4">
              <DataTablePagination
                page={page}
                pageChange={setPage}
                size={pageSize}
                sizeChange={setPageSize}
                total={total}
              />
            </div>
          </div>
        </TabsContent>
      </Tabs>
    </div>
  );
};

export default OptimizationPage;
