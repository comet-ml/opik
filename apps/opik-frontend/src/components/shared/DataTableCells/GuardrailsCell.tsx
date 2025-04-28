import React, { useState } from "react";
import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { cn } from "@/lib/utils";
import {
  HoverCard,
  HoverCardContent,
  HoverCardTrigger,
} from "@/components/ui/hover-card";
import {
  GuardrailValidation,
  GuardrailTypes,
  GuardrailResult,
  GuardrailComputedResult,
} from "@/types/guardrails";
import {
  getGuardrailComputedResult,
  GuardrailNamesLabelMap,
} from "@/constants/guardrails";

const GuardrailStatus: React.FC<{
  status: GuardrailResult;
}> = ({ status }) => (
  <div
    className={cn("size-2 rounded-full shrink-0", {
      "bg-emerald-500": status === GuardrailResult.PASSED,
      "bg-rose-500": status === GuardrailResult.FAILED,
    })}
  ></div>
);

const GeneralGuardrailData: React.FC<{ data: GuardrailComputedResult[] }> = ({
  data,
}) => {
  return data.map(({ name, status }) => (
    <div key={name} className="flex items-center gap-1.5 px-2 py-1">
      <GuardrailStatus status={status} />
      <span className="comet-body-xs-accented min-w-0 flex-1 truncate text-muted-slate">
        {GuardrailNamesLabelMap[name as GuardrailTypes]}
      </span>
      <span className="comet-body-xs-accented text-foreground first-letter:uppercase">
        {status}
      </span>
    </div>
  ));
};

const GuardrailsCell = <TData,>(context: CellContext<TData, unknown>) => {
  const guardrailValidations = context.getValue() as GuardrailValidation[];
  const [isOpen, setIsOpen] = useState(false);

  const { statusList, generalStatus } =
    getGuardrailComputedResult(guardrailValidations);

  if (!guardrailValidations.length) {
    return (
      <CellWrapper
        metadata={context.column.columnDef.meta}
        tableMetadata={context.table.options.meta}
      >
        -
      </CellWrapper>
    );
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="px-0"
    >
      <HoverCard open={isOpen} onOpenChange={setIsOpen}>
        <HoverCardTrigger asChild>
          <div className="flex items-center gap-1 px-2">
            <GuardrailStatus status={generalStatus} />
            <span className="comet-body-s truncate text-foreground first-letter:uppercase">
              {generalStatus}
            </span>
          </div>
        </HoverCardTrigger>
        <HoverCardContent
          className="w-[240px] bg-popover p-0"
          onClick={(event) => event.stopPropagation()}
          side="top"
          align="end"
        >
          <div className="relative size-full max-h-[40vh] overflow-auto px-1.5 py-2">
            <GeneralGuardrailData data={statusList} />
          </div>
        </HoverCardContent>
      </HoverCard>
    </CellWrapper>
  );
};

export default GuardrailsCell;
