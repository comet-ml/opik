import React from "react";
import { ColumnData, ROW_HEIGHT } from "@/types/shared";
import DataTableRowHeightSelector from "@/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import RefreshButton from "@/shared/RefreshButton/RefreshButton";
import { Separator } from "@/ui/separator";
import { AggregatedCandidate } from "@/types/optimizations";

interface OptimizationTrialsControlsProps {
  onRefresh: () => void;
  isFetching?: boolean;
  rowHeight: ROW_HEIGHT;
  onRowHeightChange: (height: ROW_HEIGHT) => void;
  columnsDef: ColumnData<AggregatedCandidate>[];
  selectedColumns: string[];
  onSelectedColumnsChange: (columns: string[]) => void;
  columnsOrder: string[];
  onColumnsOrderChange: (order: string[]) => void;
}

// Control order: Row size · Columns · | · Refresh.
const OptimizationTrialsControls: React.FC<OptimizationTrialsControlsProps> = ({
  onRefresh,
  isFetching,
  rowHeight,
  onRowHeightChange,
  columnsDef,
  selectedColumns,
  onSelectedColumnsChange,
  columnsOrder,
  onColumnsOrderChange,
}) => {
  return (
    <div className="flex items-center gap-2">
      <DataTableRowHeightSelector
        type={rowHeight}
        setType={onRowHeightChange}
        layout="labeled"
        size="2xs"
      />
      <ColumnsButton
        columns={columnsDef}
        selectedColumns={selectedColumns}
        onSelectionChange={onSelectedColumnsChange}
        order={columnsOrder}
        onOrderChange={onColumnsOrderChange}
        layout="labeled"
        size="2xs"
      />
      <Separator orientation="vertical" className="mx-[2px] h-4" />
      <RefreshButton
        tooltip="Refresh trials list"
        isFetching={isFetching}
        onRefresh={onRefresh}
        size="icon-2xs"
      />
    </div>
  );
};

export default OptimizationTrialsControls;
