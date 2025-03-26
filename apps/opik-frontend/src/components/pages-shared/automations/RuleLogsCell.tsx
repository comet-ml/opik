import React from "react";
import { SquareArrowOutUpRight } from "lucide-react";
import { CellContext } from "@tanstack/react-table";
import { Link } from "@tanstack/react-router";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { EvaluatorsRule } from "@/types/automations";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";

const RuleLogsCell = (context: CellContext<EvaluatorsRule, string>) => {
  const rule = context.row.original;
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="items-center justify-end p-0"
    >
      <Link
        onClick={(event) => event.stopPropagation()}
        to="/$workspaceName/automation-logs"
        params={{ workspaceName }}
        search={{
          rule_id: rule.id,
        }}
        target="_blank"
      >
        <Button variant="tableLink" size="sm">
          Show logs
          <SquareArrowOutUpRight className="ml-1.5 mt-1 size-3.5 shrink-0" />
        </Button>
      </Link>
    </CellWrapper>
  );
};

export default RuleLogsCell;
