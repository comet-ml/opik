import React, { useState } from "react";
import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { cn } from "@/lib/utils";
import {
  HoverCard,
  HoverCardContent,
  HoverCardTrigger,
} from "@/components/ui/hover-card";
import { Separator } from "@/components/ui/separator";
import {
  GuardrailValidation,
  GuardrailTypes,
  PiiSupportedEntities,
  GuardrailResult,
  GuardrailComputedResult,
} from "@/types/guardrails";
import {
  getGuardrailComputedResult,
  GuardrailNamesLabelMap,
  PIIEntitiesLabelMap,
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

const SingleGuardrailData: React.FC<{ data: GuardrailValidation }> = ({
  data,
}) => {
  return data.checks.map((guardrail, idx) => (
    <div key={guardrail.name}>
      {idx > 0 && <Separator className="my-1" />}
      <div className="comet-body-xs-accented px-2 py-0.5 text-foreground">
        {GuardrailNamesLabelMap[guardrail.name as GuardrailTypes]}
      </div>

      {guardrail.items.map((item) => (
        <div key={item.name} className="flex items-center gap-1.5 px-2 py-1">
          <GuardrailStatus status={GuardrailResult.FAILED} />
          <span className="comet-body-xs-accented min-w-0 flex-1 truncate text-muted-slate">
            {guardrail.name === GuardrailTypes.TOPIC
              ? item.name
              : PIIEntitiesLabelMap[item.name as PiiSupportedEntities] ||
                item.name}
          </span>
          <span className="comet-body-xs-accented text-foreground">
            {item.score}
          </span>
        </div>
      ))}
    </div>
  ));
};

const GuardrailsCell = <TData,>(context: CellContext<TData, unknown>) => {
  const guardrailValidations = context.getValue() as GuardrailValidation[];
  const [isOpen, setIsOpen] = useState(false);

  const hasSingleGuardrailSpan = guardrailValidations.length === 1;
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
            {hasSingleGuardrailSpan ? (
              <SingleGuardrailData data={guardrailValidations[0]} />
            ) : (
              <GeneralGuardrailData data={statusList} />
            )}
          </div>
        </HoverCardContent>
      </HoverCard>
    </CellWrapper>
  );
};

export default GuardrailsCell;
