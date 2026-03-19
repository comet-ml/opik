import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "@tanstack/react-router";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import Loader from "@/shared/Loader/Loader";
import OptimizationProgressChartContainer from "@/v1/pages-shared/experiments/OptimizationProgressChart";
import TrialConfigurationSection from "@/v1/pages-shared/experiments/TrialConfigurationSection";
import { IN_PROGRESS_OPTIMIZATION_STATUSES } from "@/lib/optimizations";
import { formatDate } from "@/lib/date";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { useOptimizationData } from "./useOptimizationData";
import { useOptimizationColumns } from "./useOptimizationColumns";
import OptimizationHeader from "./OptimizationHeader";
import OptimizationTrialsControls from "./OptimizationTrialsControls";
import OptimizationTrialsTable from "./OptimizationTrialsTable";
import OptimizationKPICards from "./OptimizationKPICards";

enum OPTIMIZATION_TAB {
  OVERVIEW = "overview",
  TRIALS = "trials",
}

const OptimizationPage: React.FC = () => {
  const navigate = useNavigate();

  const {
    workspaceName,
    optimizationId,
    optimization,
    experiments,
    candidates,
    rows,
    noDataText,
    sortableBy,

    bestCandidate,
    baselineCandidate,
    baselineExperiment,
    inProgressInfo,
    isRunningMiniBatches,
    isEvaluationSuite,
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

  const bestExperiment = useMemo(() => {
    if (!bestCandidate || !experiments.length) return undefined;
    const ids = new Set(bestCandidate.experimentIds);
    return experiments.find((e) => ids.has(e.id));
  }, [bestCandidate, experiments]);

  const handleTrialClick = useCallback(
    (candidateId: string) => {
      const candidate = candidates.find((c) => c.candidateId === candidateId);
      if (!candidate) return;
      navigate({
        to: "/$workspaceName/optimizations/$optimizationId/trials",
        params: {
          optimizationId,
          workspaceName,
        },
        search: {
          trials: candidate.experimentIds,
          trialNumber: candidate.trialNumber,
        },
      });
    },
    [candidates, navigate, optimizationId, workspaceName],
  );

  const isInProgress =
    !!optimization?.status &&
    IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization.status);

  const { columnsDef, columns } = useOptimizationColumns({
    candidates,
    columnsOrder,
    selectedColumns,
    sortableBy,
    bestCandidateId: bestCandidate?.candidateId,
    baselineCandidate,
    isEvaluationSuite,
    isInProgress,
    inProgressInfo,
    objectiveName: optimization?.objective_name,
  });

  if (isOptimizationPending || isExperimentsPending) {
    return <Loader />;
  }

  const canRerun =
    Boolean(optimization?.studio_config) &&
    Boolean(optimization?.id) &&
    optimization?.status &&
    !IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization.status);

  const isTrialsTab = activeTab === OPTIMIZATION_TAB.TRIALS;

  return (
    <div
      className={`flex flex-col pt-6 ${isTrialsTab ? "h-full" : "min-h-full"}`}
    >
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
        className="flex min-h-0 flex-1 flex-col"
      >
        <TabsList variant="underline" className="shrink-0">
          <TabsTrigger variant="underline" value={OPTIMIZATION_TAB.OVERVIEW}>
            Overview
          </TabsTrigger>
          <TabsTrigger variant="underline" value={OPTIMIZATION_TAB.TRIALS}>
            Trials
          </TabsTrigger>
        </TabsList>

        <TabsContent value={OPTIMIZATION_TAB.OVERVIEW} className="mt-0 pt-4">
          <div className="shrink-0 pb-4">
            <OptimizationKPICards
              experiments={experiments}
              baselineCandidate={baselineCandidate}
              bestCandidate={bestCandidate}
              isEvaluationSuite={isEvaluationSuite}
              objectiveName={optimization?.objective_name}
              optimizationCreatedAt={optimization?.created_at}
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
              isEvaluationSuite={isEvaluationSuite}
              inProgressInfo={inProgressInfo}
              isRunningMiniBatches={isRunningMiniBatches}
              selectedTrialId={selectedTrialId}
              onTrialSelect={setSelectedTrialId}
            />
          </div>

          {bestExperiment && (
            <div className="shrink-0 pb-4">
              <TrialConfigurationSection
                experiments={[bestExperiment]}
                title="Best trial configuration"
                referenceExperiment={
                  candidates.length > 1 ? baselineExperiment : undefined
                }
                studioConfig={optimization?.studio_config}
              />
            </div>
          )}
        </TabsContent>

        <TabsContent
          value={OPTIMIZATION_TAB.TRIALS}
          className="mt-0 flex min-h-0 flex-1 flex-col pt-4"
        >
          <div className="flex shrink-0 items-center justify-end pb-4">
            <OptimizationTrialsControls
              onRefresh={handleRefresh}
              rowHeight={height}
              onRowHeightChange={setHeight}
              columnsDef={columnsDef}
              selectedColumns={selectedColumns}
              onSelectedColumnsChange={setSelectedColumns}
              columnsOrder={columnsOrder}
              onColumnsOrderChange={setColumnsOrder}
            />
          </div>

          <div className="flex min-w-0 flex-1 overflow-auto">
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
          </div>
        </TabsContent>
      </Tabs>
    </div>
  );
};

export default OptimizationPage;
