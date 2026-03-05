import React, { useEffect, useMemo, useState } from "react";

import Loader from "@/components/shared/Loader/Loader";
import OptimizationProgressChartContainer from "@/components/pages-shared/experiments/OptimizationProgressChart";
import TrialConfigurationSection from "@/components/pages-shared/experiments/TrialConfigurationSection";
import { OPTIMIZATION_VIEW_TYPE } from "@/components/pages/CompareOptimizationsPage/OptimizationViewSelector";
import { IN_PROGRESS_OPTIMIZATION_STATUSES } from "@/lib/optimizations";
import { useCompareOptimizationsData } from "./useCompareOptimizationsData";
import { useCompareOptimizationsColumns } from "./useCompareOptimizationsColumns";
import CompareOptimizationsHeader from "./CompareOptimizationsHeader";
import OptimizationKPICards from "./OptimizationKPICards";
import CompareOptimizationsToolbar from "./CompareOptimizationsToolbar";
import CompareOptimizationsTrialsControls from "./CompareOptimizationsTrialsControls";
import CompareOptimizationsMainContent from "./CompareOptimizationsMainContent";

const CompareOptimizationsPage: React.FC = () => {
  const [view, setView] = useState<OPTIMIZATION_VIEW_TYPE>(
    OPTIMIZATION_VIEW_TYPE.TRIALS,
  );
  const [selectedTrialId, setSelectedTrialId] = useState<string | undefined>();

  const {
    optimizationId,
    optimization,
    experiments,
    candidates,
    rows,
    title,
    noDataText,

    bestCandidate,
    baselineCandidate,
    baselineExperiment,
    sortableBy,
    isOptimizationPending,
    isExperimentsPending,
    isExperimentsPlaceholderData,
    isExperimentsFetching,
    search,
    setSearch,
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
  } = useCompareOptimizationsData();

  const isOptimizationFinished =
    !!optimization?.status &&
    !IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization.status);

  const { columnsDef, columns } = useCompareOptimizationsColumns({
    candidates,
    columnsOrder,
    selectedColumns,
    sortableBy,
    isOptimizationFinished,
    bestCandidateId: bestCandidate?.candidateId,
  });

  const bestExperiment = useMemo(() => {
    if (!bestCandidate || !experiments.length) return undefined;
    const ids = new Set(bestCandidate.experimentIds);
    return experiments.find((e) => ids.has(e.id));
  }, [bestCandidate, experiments]);

  // set initial view based on optimization status when optimization changes
  useEffect(() => {
    if (optimization?.status) {
      const isInProgress = IN_PROGRESS_OPTIMIZATION_STATUSES.includes(
        optimization.status,
      );
      setView(
        isInProgress
          ? OPTIMIZATION_VIEW_TYPE.LOGS
          : OPTIMIZATION_VIEW_TYPE.TRIALS,
      );
    }
  }, [optimizationId, optimization?.status]);

  if (isOptimizationPending || isExperimentsPending) {
    return <Loader />;
  }

  const isStudioOptimization = Boolean(optimization?.studio_config);
  const canRerun =
    isStudioOptimization &&
    Boolean(optimization?.id) &&
    optimization?.status &&
    !IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization.status);
  const showTrialsView =
    !isStudioOptimization || view === OPTIMIZATION_VIEW_TYPE.TRIALS;

  return (
    <div className="flex h-full flex-col pt-6">
      <div className="shrink-0 pb-4">
        <CompareOptimizationsHeader
          title={title}
          status={optimization?.status}
          optimizationId={optimization?.id}
          isStudioOptimization={isStudioOptimization}
          canRerun={canRerun}
          bestExperiment={bestExperiment}
        />
      </div>

      <div className="shrink-0 pb-4">
        <OptimizationKPICards
          experiments={experiments}
          baselineCandidate={baselineCandidate}
          bestCandidate={bestCandidate}
        />
      </div>

      {bestExperiment && (
        <div className="shrink-0 pb-4">
          <TrialConfigurationSection
            experiments={[bestExperiment]}
            title="Best Configuration"
            referenceExperiment={baselineExperiment}
          />
        </div>
      )}

      <div className="shrink-0 pb-6">
        <OptimizationProgressChartContainer
          candidates={candidates}
          bestCandidateId={bestCandidate?.candidateId}
          objectiveName={optimization?.objective_name}
          status={optimization?.status}
          selectedTrialId={selectedTrialId}
          onTrialSelect={setSelectedTrialId}
        />
      </div>

      <div className="-mt-4 flex shrink-0 flex-wrap items-center justify-between gap-x-8 gap-y-2 pb-6 pt-4">
        <div className="flex items-center gap-2">
          <CompareOptimizationsToolbar
            isStudioOptimization={isStudioOptimization}
            view={view}
            onViewChange={setView}
            search={search!}
            onSearchChange={setSearch}
          />
        </div>
        {showTrialsView && (
          <CompareOptimizationsTrialsControls
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

      <div className="min-h-[500px] flex-1 pb-6">
        <CompareOptimizationsMainContent
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
          highlightedTrialId={selectedTrialId ?? bestCandidate?.id}
          bestExperiment={bestExperiment}
          showLoadingOverlay={
            isExperimentsPlaceholderData && isExperimentsFetching
          }
        />
      </div>
    </div>
  );
};

export default CompareOptimizationsPage;
