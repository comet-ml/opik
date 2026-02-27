import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Separator } from "@/components/ui/separator";
import {
  BehaviorDisplayRow,
  LLMJudgeConfig,
  MetricType,
} from "@/types/evaluation-suites";

function ExpectedBehaviorCell(
  context: CellContext<BehaviorDisplayRow, unknown>,
): React.ReactElement | null {
  const row = context.row.original;

  if (row.type !== MetricType.LLM_AS_JUDGE) return null;

  const assertions = (row.config as LLMJudgeConfig).assertions ?? [];
  const displayText = assertions.join(", ");

  const tooltipContent =
    assertions.length <= 1 ? null : (
      <div className="flex flex-col gap-1">
        {assertions.map((assertion, index) => (
          <div key={index}>
            <div className="flex gap-1.5">
              <span className="comet-body-xs-accented shrink-0">
                {index + 1}.
              </span>
              <span className="whitespace-pre-line break-words">
                {assertion}
              </span>
            </div>
            {index < assertions.length - 1 && <Separator className="my-1" />}
          </div>
        ))}
      </div>
    );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <TooltipWrapper content={tooltipContent}>
        <span className="cursor-default truncate">{displayText}</span>
      </TooltipWrapper>
    </CellWrapper>
  );
}

export default ExpectedBehaviorCell;
