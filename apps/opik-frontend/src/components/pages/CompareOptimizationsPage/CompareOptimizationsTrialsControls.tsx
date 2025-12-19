import React from "react";
import { RotateCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ColumnData, ROW_HEIGHT } from "@/types/shared";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import DataTableRowHeightSelector from "@/components/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import { Experiment } from "@/types/datasets";

interface CompareOptimizationsTrialsControlsProps {
  onRefresh: () => void;
  rowHeight: ROW_HEIGHT;
  onRowHeightChange: (height: ROW_HEIGHT) => void;
  columnsDef: ColumnData<Experiment>[];
  selectedColumns: string[];
  onSelectedColumnsChange: (columns: string[]) => void;
  columnsOrder: string[];
  onColumnsOrderChange: (order: string[]) => void;
}

const CompareOptimizationsTrialsControls: React.FC<
  CompareOptimizationsTrialsControlsProps
> = ({
  onRefresh,
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
      <TooltipWrapper content="Refresh trials list">
        <Button
          variant="outline"
          size="icon-sm"
          className="shrink-0"
          onClick={onRefresh}
        >
          <RotateCw />
        </Button>
      </TooltipWrapper>
      <DataTableRowHeightSelector
        type={rowHeight}
        setType={onRowHeightChange}
      />
      <ColumnsButton
        columns={columnsDef}
        selectedColumns={selectedColumns}
        onSelectionChange={onSelectedColumnsChange}
        order={columnsOrder}
        onOrderChange={onColumnsOrderChange}
      />
    </div>
  );
};

export default CompareOptimizationsTrialsControls;
