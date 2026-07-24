import React, { useCallback } from "react";
import { ColumnPinningState, ColumnSort, Row } from "@tanstack/react-table";
import { ColumnDef } from "@tanstack/react-table";
import { ROW_HEIGHT, OnChangeFn } from "@/types/shared";
import { AggregatedCandidate } from "@/types/optimizations";
import DataTable from "@/shared/DataTable/DataTable";
import DataTableNoData from "@/shared/DataTableNoData/DataTableNoData";
import DataTableVirtualBody from "@/shared/DataTable/DataTableVirtualBody";
import PageBodyStickyTableWrapper from "@/v2/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";

export const getRowId = (e: AggregatedCandidate) => e.id;

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [],
  right: [],
};

interface OptimizationTrialsTableProps {
  columns: ColumnDef<AggregatedCandidate>[];
  rows: AggregatedCandidate[];
  onRowClick: (row: AggregatedCandidate) => void;
  rowHeight: ROW_HEIGHT;
  noDataText: string;
  sortedColumns: ColumnSort[];
  onSortChange: OnChangeFn<ColumnSort[]>;
  columnsWidth: Record<string, number>;
  onColumnsWidthChange: OnChangeFn<Record<string, number>>;
  highlightedTrialId?: string;
  showLoadingOverlay?: boolean;
}

const OptimizationTrialsTable: React.FC<OptimizationTrialsTableProps> = ({
  columns,
  rows,
  onRowClick,
  rowHeight,
  noDataText,
  sortedColumns,
  onSortChange,
  columnsWidth,
  onColumnsWidthChange,
  highlightedTrialId,
  showLoadingOverlay,
}) => {
  const getRowClassName = useCallback(
    (row: Row<AggregatedCandidate>) => {
      if (highlightedTrialId && row.id === highlightedTrialId) {
        return "comet-table-row-best";
      }
      return "";
    },
    [highlightedTrialId],
  );

  return (
    <DataTable
      columns={columns}
      data={rows}
      onRowClick={onRowClick}
      sortConfig={{
        enabled: true,
        sorting: sortedColumns,
        setSorting: onSortChange,
      }}
      resizeConfig={{
        enabled: true,
        columnSizing: columnsWidth,
        onColumnResize: onColumnsWidthChange,
      }}
      getRowId={getRowId}
      getRowClassName={getRowClassName}
      rowHeight={rowHeight}
      columnPinning={DEFAULT_COLUMN_PINNING}
      noData={<DataTableNoData title={noDataText} />}
      TableBody={DataTableVirtualBody}
      TableWrapper={PageBodyStickyTableWrapper}
      stickyHeader
      showLoadingOverlay={showLoadingOverlay}
    />
  );
};

export default OptimizationTrialsTable;
