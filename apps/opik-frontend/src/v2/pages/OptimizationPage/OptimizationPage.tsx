import React, { useCallback, useEffect, useMemo, useState } from "react";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import Loader from "@/shared/Loader/Loader";
import SearchInput from "@/shared/SearchInput/SearchInput";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
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
import TrialSidebar from "./TrialSidebar/TrialSidebar";
import TrialSidebarContent from "./TrialSidebar/TrialSidebarContent";
import { computeCandidateStatuses } from "@/v2/pages-shared/experiments/OptimizationProgressChart/optimizationChartUtils";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { usePermissions } from "@/contexts/PermissionsContext";

enum OPTIMIZATION_TAB {
  OVERVIEW = "overview",
  TRIALS = "trials",
}

const OptimizationPage: React.FC = () => {
  const {
    permissions: { canUseOptimizationStudio },
  } = usePermissions();

  const {
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
    improvedOverBaseline,
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
    trialSidebar,
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

  const { openTrial } = trialSidebar;

  const handleTrialClick = useCallback(
    (candidateId: string) => {
      const candidate = candidates.find((c) => c.candidateId === candidateId);
      if (!candidate) return;
      openTrial(candidate);
    },
    [candidates, openTrial],
  );

  // The sidebar's experiments come from the run's already-loaded list — the
  // sidebar itself fetches nothing.
  const trialExperiments = useMemo(
    () => experiments.filter((e) => trialSidebar.experimentIds.includes(e.id)),
    [experiments, trialSidebar.experimentIds],
  );

  const isInProgress =
    !!optimization?.status &&
    IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization.status);

  // A finished run where no trial strictly beat the baseline: the optimizer
  // kept the original prompt. Only assert this once the run is completed —
  // mid-run the best candidate can still change. When it holds, present the
  // kept original (baseline) as the result prompt, matching the SDK.
  const noImprovement =
    optimization?.status === OPTIMIZATION_STATUS.COMPLETED &&
    improvedOverBaseline === false;
  const resultCandidate = noImprovement
    ? baselineCandidate ?? bestCandidate
    : bestCandidate;

  // Single status source for the chart, the trials table, and the sidebar's
  // status card.
  const statusMap = useMemo(
    () =>
      computeCandidateStatuses(
        candidates,
        isTestSuite,
        isInProgress,
        inProgressInfo,
      ),
    [candidates, isTestSuite, isInProgress, inProgressInfo],
  );

  // The candidate behind the open sidebar; matched by trial number with an
  // experiment-ids fallback for deep links minted before numbering.
  const activeTrialCandidate = useMemo(
    () =>
      candidates.find((c) => c.trialNumber === trialSidebar.trialNumber) ??
      candidates.find((c) =>
        c.experimentIds.some((id) => trialSidebar.experimentIds.includes(id)),
      ),
    [candidates, trialSidebar.trialNumber, trialSidebar.experimentIds],
  );

  const { columnsDef, columns } = useOptimizationColumns({
    experiments,
    baselineExperiment,
    columnsOrder,
    selectedColumns,
    sortableBy,
    bestCandidateId: bestCandidate?.candidateId,
    baselineCandidate,
    isTestSuite,
    statusMap,
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
    <PageBodyScrollContainer>
      <PageBodyStickyContainer
        className="py-4"
        direction="horizontal"
        limitWidth
      >
        <OptimizationHeader
          optimization={optimization}
          status={optimization?.status}
          optimizationId={optimization?.id}
          isStudioOptimization={Boolean(optimization?.studio_config)}
          canRerun={canRerun}
        />
      </PageBodyStickyContainer>

      <Tabs
        value={activeTab}
        onValueChange={setActiveTab}
        className="flex flex-col"
      >
        <PageBodyStickyContainer direction="horizontal" limitWidth>
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
        </PageBodyStickyContainer>

        <TabsContent value={OPTIMIZATION_TAB.OVERVIEW} className="mt-0">
          <PageBodyStickyContainer
            className="flex flex-col gap-3 pt-3"
            direction="horizontal"
            limitWidth
          >
            {optimization &&
              optimization.status === OPTIMIZATION_STATUS.ERROR && (
                <div className="shrink-0">
                  <RunErrorPanel optimization={optimization} />
                </div>
              )}
            <div className="shrink-0">
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
                  IN_PROGRESS_OPTIMIZATION_STATUSES.includes(
                    optimization.status,
                  )
                }
              />
            </div>

            <div className="shrink-0">
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
                suppressBestTrialCard={trialSidebar.open}
              />
            </div>

            {resultCandidate && (
              <div className="shrink-0">
                <BestTrialPrompt
                  bestCandidate={resultCandidate}
                  candidates={candidates}
                  experiments={experiments}
                  noImprovement={noImprovement}
                  onViewTrial={() =>
                    handleTrialClick(resultCandidate.candidateId)
                  }
                />
              </div>
            )}
          </PageBodyStickyContainer>
        </TabsContent>

        <TabsContent value={OPTIMIZATION_TAB.TRIALS} className="mt-0">
          <PageBodyStickyContainer
            className="flex flex-wrap items-center justify-between gap-x-8 gap-y-2 pb-2 pt-3"
            direction="bidirectional"
            limitWidth
          >
            <SearchInput
              searchText={search ?? ""}
              setSearchText={setSearch}
              placeholder="Search"
              className="w-[200px] shrink-0"
              dimension="xs"
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
          </PageBodyStickyContainer>

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
          <PageBodyStickyContainer
            className="py-4"
            direction="horizontal"
            limitWidth
          >
            <DataTablePagination
              page={page}
              pageChange={setPage}
              size={pageSize}
              sizeChange={setPageSize}
              total={total}
            />
          </PageBodyStickyContainer>
        </TabsContent>
      </Tabs>

      <TrialSidebar
        open={trialSidebar.open}
        onClose={trialSidebar.close}
        trialNumber={trialSidebar.trialNumber}
        trialExperiments={trialExperiments}
      >
        <TrialSidebarContent
          optimization={optimization}
          experimentIds={trialSidebar.experimentIds}
          trialExperiments={trialExperiments}
          allExperiments={experiments}
          candidates={candidates}
          candidate={activeTrialCandidate}
          isTestSuite={isTestSuite}
          status={
            activeTrialCandidate
              ? statusMap.get(activeTrialCandidate.candidateId)
              : undefined
          }
          isBest={
            !!activeTrialCandidate &&
            activeTrialCandidate.candidateId === bestCandidate?.candidateId
          }
          stepIndex={activeTrialCandidate?.stepIndex}
          tab={trialSidebar.tab}
          promptView={trialSidebar.promptView}
          onTabChange={trialSidebar.setTab}
        />
      </TrialSidebar>
    </PageBodyScrollContainer>
  );
};

export default OptimizationPage;
