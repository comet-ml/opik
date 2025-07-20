import React from "react";
import { CellContext } from "@tanstack/react-table";
import { EvaluatorsRule } from "@/types/automations";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

const RuleEnabledCell = (
  context: CellContext<EvaluatorsRule, unknown>,
): React.ReactElement => {
  const rule = context.row.original;

  // Default to true if enabled property doesn't exist yet
  const isEnabled = rule?.enabled ?? true;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <span className="text-center text-sm">
        {isEnabled ? "Enabled" : "Disabled"}
      </span>
    </CellWrapper>
  );
};

export default RuleEnabledCell;
