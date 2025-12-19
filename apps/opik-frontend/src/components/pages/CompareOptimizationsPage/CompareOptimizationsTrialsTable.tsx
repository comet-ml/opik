import React from "react";
import { ColumnPinningState, ColumnSort } from "@tanstack/react-table";
import { ColumnDef } from "@tanstack/react-table";
import { ROW_HEIGHT } from "@/types/shared";
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
      className="comet-sticky-table comet-compare-optimizations-table overflow-x-auto overflow-y-hidden rounded-md"
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
  onSortChange: (sorting: ColumnSort[]) => void;
  columnsWidth: Record<string, number>;
  onColumnsWidthChange: (width: Record<string, number>) => void;
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
}) => {
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
