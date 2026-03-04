import { CellContext } from "@tanstack/react-table";
import { FileText } from "lucide-react";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";
import { Tag } from "@/components/ui/tag";
import { EnrichedBlueprintValue } from "@/types/agent-configs";
import { formatNumericData } from "@/lib/utils";

const BlueprintValueCell = (
  context: CellContext<EnrichedBlueprintValue, string>,
) => {
  const value = context.getValue();
  const row = context.row.original;

  const renderValue = () => {
    switch (row.type) {
      case "int":
      case "float": {
        const num = Number(value);
        return (
          <span className="truncate">
            {isNaN(num) ? value : formatNumericData(num)}
          </span>
        );
      }
      case "boolean": {
        const isTruthy = value === "true";
        return (
          <Tag size="md" variant={isTruthy ? "green" : "gray"}>
            {isTruthy ? "True" : "False"}
          </Tag>
        );
      }
      case "Prompt":
        return (
          <div className="flex items-center gap-1.5 overflow-hidden">
            <FileText className="size-3.5 shrink-0 text-muted-slate" />
            <div className="flex flex-col overflow-hidden">
              <span className="truncate">{row.promptName ?? value}</span>
              <span className="truncate text-xs text-muted-slate">{value}</span>
            </div>
          </div>
        );
      default:
        return (
          <CellTooltipWrapper content={value}>
            <span className="truncate">{value}</span>
          </CellTooltipWrapper>
        );
    }
  };

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {renderValue()}
    </CellWrapper>
  );
};

export default BlueprintValueCell;
