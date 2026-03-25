import React from "react";
import { CellContext, ColumnMeta, TableMeta } from "@tanstack/react-table";
import { CheckCheck } from "lucide-react";
import { DatasetItem, Evaluator } from "@/types/datasets";
import {
  Tooltip,
  TooltipContent,
  TooltipPortal,
  TooltipTrigger,
} from "@/ui/tooltip";
import { useEffectiveItemAssertions } from "@/hooks/useEffectiveItemAssertions";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { AssertionsListTooltipContent } from "@/v2/pages-shared/experiments/EvaluationSuiteExperiment/AssertionsListTooltipContent";

interface AssertionsCountCellInnerProps {
  itemId: string;
  serverEvaluators: Evaluator[];
  metadata: ColumnMeta<DatasetItem, unknown> | undefined;
  tableMetadata: TableMeta<DatasetItem> | undefined;
}

const AssertionsCountCellInner: React.FC<AssertionsCountCellInnerProps> = ({
  itemId,
  serverEvaluators,
  metadata,
  tableMetadata,
}) => {
  const assertions = useEffectiveItemAssertions(itemId, serverEvaluators);
  const count = assertions.length;

  if (count === 0) {
    return (
      <CellWrapper
        metadata={metadata}
        tableMetadata={tableMetadata}
        className="justify-center"
      >
        <span className="text-muted-slate">&mdash;</span>
      </CellWrapper>
    );
  }

  return (
    <CellWrapper
      metadata={metadata}
      tableMetadata={tableMetadata}
      className="justify-center p-0"
    >
      <Tooltip>
        <TooltipTrigger asChild>
          <div className="flex size-full cursor-pointer items-center justify-center px-3 py-2">
            <div className="flex items-center gap-1 rounded bg-thread-active px-1.5 py-0.5">
              <CheckCheck className="size-3 text-muted-foreground" />
              <span className="comet-body-s-accented text-muted-foreground">
                {count}
              </span>
            </div>
          </div>
        </TooltipTrigger>
        <TooltipPortal>
          <TooltipContent
            side="bottom"
            collisionPadding={16}
            className="max-w-fit p-0"
          >
            <AssertionsListTooltipContent assertions={assertions} />
          </TooltipContent>
        </TooltipPortal>
      </Tooltip>
    </CellWrapper>
  );
};

export const AssertionsCountCell: React.FC<
  CellContext<DatasetItem, unknown>
> = (context) => {
  const item = context.row.original;

  return (
    <AssertionsCountCellInner
      itemId={item.id}
      serverEvaluators={item.evaluators ?? []}
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    />
  );
};
