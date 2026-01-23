import React, { useCallback } from "react";
import { ColumnPinningState, ColumnSort, Row } from "@tanstack/react-table";
import { ColumnDef } from "@tanstack/react-table";
import { ROW_HEIGHT, OnChangeFn } from "@/types/shared";
import { Experiment } from "@/types/datasets";
import { Card } from "@/components/ui/card";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableVirtualBody from "@/components/shared/DataTable/DataTableVirtualBody";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import { DataTableWrapperProps } from "@/components/shared/DataTable/DataTableWrapper";
import { TABLE_WRAPPER_ATTRIBUTE } from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";

export const getRowId = (e: Experiment) => e.id;

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: ["name"],
  right: [],
};

const StickyTableWrapperWithBorder: React.FC<DataTableWrapperProps> = ({
  children,
}) => {
  return (
    <div
      className="comet-sticky-table comet-compare-optimizations-table h-full overflow-auto rounded-md"
      {...{
        [TABLE_WRAPPER_ATTRIBUTE]: "",
      }}
    >
      {children}
    </div>
  );
};

interface CompareOptimizationsTrialsTableProps {
  columns: ColumnDef<Experiment>[];
  rows: Experiment[];
  onRowClick: (row: Experiment) => void;
  rowHeight: ROW_HEIGHT;
  noDataText: string;
  sortedColumns: ColumnSort[];
  onSortChange: OnChangeFn<ColumnSort[]>;
  columnsWidth: Record<string, number>;
  onColumnsWidthChange: OnChangeFn<Record<string, number>>;
  highlightedTrialId?: string;
}

const CompareOptimizationsTrialsTable: React.FC<
  CompareOptimizationsTrialsTableProps
> = ({
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
}) => {
  const getRowClassName = useCallback(
    (row: Row<Experiment>) => {
      if (highlightedTrialId && row.id === highlightedTrialId) {
        return "[&_td]:bg-[#e6f7ed] [&:hover_td]:!bg-[#d0f0dc]";
      }
      return "";
    },
    [highlightedTrialId],
  );

  return (
    <Card className="h-full flex-1 overflow-hidden">
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
        TableWrapper={StickyTableWrapperWithBorder}
        TableBody={DataTableVirtualBody}
        stickyHeader
      />
    </Card>
  );
};

export default CompareOptimizationsTrialsTable;
