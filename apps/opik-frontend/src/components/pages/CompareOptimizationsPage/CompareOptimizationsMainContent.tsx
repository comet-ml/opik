import React from "react";
import { ColumnDef, ColumnSort } from "@tanstack/react-table";
import { ROW_HEIGHT, OnChangeFn } from "@/types/shared";
import { Experiment } from "@/types/datasets";
import { Optimization } from "@/types/optimizations";
import { OPTIMIZATION_VIEW_TYPE } from "@/components/pages/CompareOptimizationsPage/OptimizationViewSelector";
import OptimizationLogs from "@/components/pages-shared/optimizations/OptimizationLogs/OptimizationLogs";
import CompareOptimizationsTrialsTable from "./CompareOptimizationsTrialsTable";
import CompareOptimizationsConfiguration from "./CompareOptimizationsConfiguration";

type CompareOptimizationsMainContentProps = {
  view: OPTIMIZATION_VIEW_TYPE;
  isStudioOptimization: boolean;
  optimization: Optimization | undefined;
  columns: ColumnDef<Experiment>[];
  rows: Experiment[];
  rowHeight: ROW_HEIGHT;
  noDataText: string;
  sortedColumns: ColumnSort[];
  columnsWidth: Record<string, number>;
  onRowClick: (row: Experiment) => void;
  onSortChange: OnChangeFn<ColumnSort[]>;
  onColumnsWidthChange: OnChangeFn<Record<string, number>>;
  highlightedTrialId?: string;
  bestExperiment?: Experiment;
  showLoadingOverlay?: boolean;
};

const CompareOptimizationsMainContent: React.FC<
  CompareOptimizationsMainContentProps
> = ({
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
    <div className="flex max-h-[500px] min-w-0 flex-1 overflow-auto">
      {showLogsView && <OptimizationLogs optimization={optimization!} />}
      {showTrialsView && (
        <CompareOptimizationsTrialsTable
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
        <CompareOptimizationsConfiguration
          studioConfig={optimization.studio_config}
          datasetId={optimization.dataset_id}
          optimizationId={optimization.id}
          bestExperiment={bestExperiment}
        />
      )}
    </div>
  );
};

export default CompareOptimizationsMainContent;
