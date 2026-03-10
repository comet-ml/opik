import React, { useCallback, useMemo, useState } from "react";
import { useNavigate } from "@tanstack/react-router";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import Loader from "@/components/shared/Loader/Loader";
import OptimizationProgressChartContainer from "@/components/pages-shared/experiments/OptimizationProgressChart";
import TrialConfigurationSection from "@/components/pages-shared/experiments/TrialConfigurationSection";
import { IN_PROGRESS_OPTIMIZATION_STATUSES } from "@/lib/optimizations";
import { useOptimizationData } from "./useOptimizationData";
import { useOptimizationColumns } from "./useOptimizationColumns";
import OptimizationHeader from "./OptimizationHeader";
import OptimizationToolbar from "./OptimizationToolbar";
import OptimizationTrialsControls from "./OptimizationTrialsControls";
import OptimizationMainContent from "./OptimizationMainContent";
import OptimizationKPICards from "./OptimizationKPICards";
import { OPTIMIZATION_VIEW_TYPE } from "./OptimizationViewSelector";

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
    title,
    rows,
    noDataText,
    sortableBy,

    bestCandidate,
    baselineCandidate,
    baselineExperiment,
    inProgressInfo,
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
  const [view, setView] = useState<OPTIMIZATION_VIEW_TYPE>(
    OPTIMIZATION_VIEW_TYPE.LOGS,
  );
  const [activeTab, setActiveTab] = useState<string>(OPTIMIZATION_TAB.OVERVIEW);

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

  const isFinished =
    !!optimization?.status &&
    !IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization.status);

  const isStudioOptimization = Boolean(optimization?.studio_config);

  const { columnsDef, columns } = useOptimizationColumns({
    candidates,
    columnsOrder,
    selectedColumns,
    sortableBy,
    isOptimizationFinished: isFinished,
    bestCandidateId: bestCandidate?.candidateId,
    isEvaluationSuite,
    inProgressStepIndex: inProgressInfo?.stepIndex,
  });

  if (isOptimizationPending || isExperimentsPending) {
    return <Loader />;
  }

  const canRerun =
    isStudioOptimization &&
    Boolean(optimization?.id) &&
    optimization?.status &&
    !IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization.status);

  const showTrialsView =
    !isStudioOptimization || view === OPTIMIZATION_VIEW_TYPE.TRIALS;

  const isTrialsTab = activeTab === OPTIMIZATION_TAB.TRIALS;

  return (
    <div
      className={`flex flex-col pt-6 ${isTrialsTab ? "h-full" : "min-h-full"}`}
    >
      <div className="shrink-0 pb-4">
        <OptimizationHeader
          title={title}
          status={optimization?.status}
          optimizationId={optimization?.id}
          isStudioOptimization={isStudioOptimization}
          canRerun={canRerun}
          bestExperiment={bestExperiment}
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
              selectedTrialId={selectedTrialId}
              onTrialSelect={setSelectedTrialId}
            />
          </div>

          {bestExperiment && (
            <div className="shrink-0 pb-4">
              <TrialConfigurationSection
                experiments={[bestExperiment]}
                title="Best trial configuration"
                referenceExperiment={baselineExperiment}
                studioConfig={optimization?.studio_config}
              />
            </div>
          )}
        </TabsContent>

        <TabsContent
          value={OPTIMIZATION_TAB.TRIALS}
          className="mt-0 flex min-h-0 flex-1 flex-col pt-4"
        >
          <div className="flex shrink-0 items-center justify-between pb-4">
            <OptimizationToolbar
              isStudioOptimization={isStudioOptimization}
              view={view}
              onViewChange={setView}
            />
            {showTrialsView && (
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
            )}
          </div>

          <OptimizationMainContent
            view={view}
            isStudioOptimization={isStudioOptimization}
            optimization={optimization}
            columns={columns}
            rows={rows}
            rowHeight={height}
            noDataText={noDataText}
            sortedColumns={sortedColumns}
            columnsWidth={columnsWidth}
            onRowClick={handleRowClick}
            onSortChange={setSortedColumns}
            onColumnsWidthChange={setColumnsWidth}
            highlightedTrialId={selectedTrialId}
            bestExperiment={bestExperiment}
            showLoadingOverlay={
              isExperimentsPlaceholderData && isExperimentsFetching
            }
          />
        </TabsContent>
      </Tabs>
    </div>
  );
};

export default OptimizationPage;
