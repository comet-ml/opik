import React from "react";
import { ColumnDef, ColumnSort } from "@tanstack/react-table";
import { ROW_HEIGHT, OnChangeFn } from "@/types/shared";
import { Experiment } from "@/types/datasets";
import { AggregatedCandidate, Optimization } from "@/types/optimizations";
import { OPTIMIZATION_VIEW_TYPE } from "@/v2/pages/OptimizationPage/OptimizationViewSelector";
import OptimizationLogs from "@/v2/pages-shared/optimizations/OptimizationLogs/OptimizationLogs";
import OptimizationTrialsTable from "./OptimizationTrialsTable";
import OptimizationConfiguration from "./OptimizationConfiguration";

type OptimizationMainContentProps = {
  view: OPTIMIZATION_VIEW_TYPE;
  isStudioOptimization: boolean;
  optimization: Optimization | undefined;
  columns: ColumnDef<AggregatedCandidate>[];
  rows: AggregatedCandidate[];
  rowHeight: ROW_HEIGHT;
  noDataText: string;
  sortedColumns: ColumnSort[];
  columnsWidth: Record<string, number>;
  onRowClick: (row: AggregatedCandidate) => void;
  onSortChange: OnChangeFn<ColumnSort[]>;
  onColumnsWidthChange: OnChangeFn<Record<string, number>>;
  highlightedTrialId?: string;
  bestExperiment?: Experiment;
  showLoadingOverlay?: boolean;
};

const OptimizationMainContent: React.FC<OptimizationMainContentProps> = ({
  view,
  isStudioOptimization,
  optimization,
  columns,
  rows,
  rowHeight,
  noDataText,
  sortedColumns,
  columnsWidth,
  onRowClick,
  onSortChange,
  onColumnsWidthChange,
  highlightedTrialId,
  bestExperiment,
  showLoadingOverlay,
}) => {
  const showTrialsView =
    !isStudioOptimization || view === OPTIMIZATION_VIEW_TYPE.TRIALS;
  const showLogsView =
    isStudioOptimization && view === OPTIMIZATION_VIEW_TYPE.LOGS;
  const showConfigurationView =
    isStudioOptimization && view === OPTIMIZATION_VIEW_TYPE.CONFIGURATION;

  return (
    <div className="flex min-w-0 flex-1 overflow-auto">
      {showLogsView && <OptimizationLogs optimization={optimization!} />}
      {showTrialsView && (
        <OptimizationTrialsTable
          columns={columns}
          rows={rows}
          onRowClick={onRowClick}
          rowHeight={rowHeight}
          noDataText={noDataText}
          sortedColumns={sortedColumns}
          onSortChange={onSortChange}
          columnsWidth={columnsWidth}
          onColumnsWidthChange={onColumnsWidthChange}
          highlightedTrialId={highlightedTrialId}
          showLoadingOverlay={showLoadingOverlay}
        />
      )}
      {showConfigurationView && optimization?.studio_config && (
        <OptimizationConfiguration
          studioConfig={optimization.studio_config}
          datasetId={optimization.dataset_id}
          optimizationId={optimization.id}
          bestExperiment={bestExperiment}
        />
      )}
    </div>
  );
};

export default OptimizationMainContent;
