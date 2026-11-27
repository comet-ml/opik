import React, { useState } from "react";
import { ListFilter, Plus, X } from "lucide-react";

import { Button } from "@/ui/button";
import { cn } from "@/lib/utils";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { InlineFilterRow } from "@/shared/filter-chips/chips/QueryBuilderChip/FilterRow";
import { OperatorCell } from "@/shared/filter-chips/chips/QueryBuilderChip/cells/OperatorCell";
import QueryBuilderChipPopoverContent from "@/shared/filter-chips/chips/QueryBuilderChip/QueryBuilderChipPopoverContent";
import NumericChipPopoverContent from "@/shared/filter-chips/chips/NumericChip/NumericChipPopoverContent";
import TimeChipPopoverContent from "@/shared/filter-chips/chips/TimeChip/TimeChipPopoverContent";
import {
  ChipDefinition,
  ChipValue,
  NumericChipValue,
  QueryBuilderChipValue,
  SingleSelectChipValue,
  TimeChipValue,
} from "@/shared/filter-chips/types";

export interface AutomationFilterBlocksValue {
  /** Chip ids in the order their blocks were added. */
  ids: string[];
  values: Record<string, ChipValue>;
}

type AutomationFilterBlocksProps = {
  definitions: ChipDefinition[];
  value: AutomationFilterBlocksValue;
  onChange: (value: AutomationFilterBlocksValue) => void;
  /** Blocks to mark as needing to be completed or removed. */
  incompleteIds?: string[];
};

const BlockHeader: React.FC<{
  children: React.ReactNode;
  onRemove: () => void;
}> = ({ children, onRemove }) => (
  <div className="flex h-8 items-center gap-1.5 pl-2 pr-1">
    <span className="flex size-4 shrink-0 items-center justify-center rounded-[4px] bg-lime-400">
      <ListFilter className="size-3 text-black" />
    </span>
    <div className="flex min-w-0 flex-1 items-center">{children}</div>
    <Button
      type="button"
      variant="minimal"
      size="icon-2xs"
      onClick={onRemove}
      aria-label="Remove filter"
    >
      <X />
    </Button>
  </div>
);

/**
 * Automation conditions as one block per Logs filter. Every row of every block must match — the same as
 * the Logs filters — and each field can be added once, so a field already in use is not offered again.
 */
const AutomationFilterBlocks: React.FC<AutomationFilterBlocksProps> = ({
  definitions,
  value,
  onChange,
  incompleteIds = [],
}) => {
  const [isPicking, setIsPicking] = useState(false);

  const definitionsById = new Map(definitions.map((def) => [def.id, def]));
  const blocks = value.ids.flatMap((id) => {
    const definition = definitionsById.get(id);
    return definition ? [definition] : [];
  });
  const available = definitions.filter((def) => !value.ids.includes(def.id));

  const setBlockValue = (id: string, next: ChipValue | undefined) => {
    const values = { ...value.values };
    if (next === undefined) delete values[id];
    else values[id] = next;
    onChange({ ids: value.ids, values });
  };

  const addBlock = (id: string) => {
    const definition = definitionsById.get(id);
    setIsPicking(false);
    onChange({
      ids: [...value.ids, id],
      // A toggle has nothing to fill in, so adding it is what applies it.
      values:
        definition?.kind === "boolean"
          ? { ...value.values, [id]: { applied: true } }
          : value.values,
    });
  };

  const removeBlock = (id: string) => {
    const values = { ...value.values };
    delete values[id];
    onChange({ ids: value.ids.filter((blockId) => blockId !== id), values });
  };

  const renderBody = (definition: ChipDefinition) => {
    const blockValue = value.values[definition.id];
    const onClear = () => setBlockValue(definition.id, undefined);

    switch (definition.kind) {
      case "query-builder":
        return (
          <QueryBuilderChipPopoverContent
            inline
            definition={definition}
            value={blockValue as QueryBuilderChipValue | undefined}
            onApply={(next) => setBlockValue(definition.id, next)}
            onClear={onClear}
          />
        );
      case "numeric":
        return (
          <NumericChipPopoverContent
            inline
            definition={definition}
            value={blockValue as NumericChipValue | undefined}
            onApply={(next) => setBlockValue(definition.id, next)}
            onClear={onClear}
          />
        );
      case "time":
        return (
          <TimeChipPopoverContent
            inline
            fieldLabel={definition.label}
            value={blockValue as TimeChipValue | undefined}
            onApply={(next) => setBlockValue(definition.id, next)}
            onClear={onClear}
          />
        );
      case "single-select":
        return (
          <InlineFilterRow>
            <OperatorCell
              value={
                (blockValue as SingleSelectChipValue | undefined)?.value ?? ""
              }
              options={definition.options}
              onSelect={(next: string) =>
                setBlockValue(definition.id, { value: next })
              }
              ariaLabel={definition.label}
              placeholder={`Select ${definition.label.toLowerCase()}`}
              className="flex-1 justify-between"
            />
          </InlineFilterRow>
        );
      case "boolean":
        return null;
    }
  };

  return (
    <div className="flex flex-col gap-1.5">
      {blocks.map((definition) => {
        const body = renderBody(definition);
        const isIncomplete = incompleteIds.includes(definition.id);
        return (
          <div
            key={definition.id}
            className={cn(
              "flex flex-col rounded-md border bg-soft-background",
              isIncomplete ? "border-destructive" : "border-border",
            )}
            data-testid={`automation-filter-block-${definition.id}`}
          >
            <BlockHeader onRemove={() => removeBlock(definition.id)}>
              <span className="comet-body-xs-accented text-muted-slate">
                {definition.label}
              </span>
            </BlockHeader>
            {(body || isIncomplete) && (
              <div className="flex flex-col gap-1.5 px-1.5 pb-1.5">
                {body}
                {isIncomplete && (
                  <p className="comet-body-xs px-0.5 text-destructive">
                    Complete this filter or remove it
                  </p>
                )}
              </div>
            )}
          </div>
        );
      })}

      {isPicking && (
        <div className="rounded-md border border-primary bg-soft-background">
          <BlockHeader onRemove={() => setIsPicking(false)}>
            <SelectBox
              value=""
              onChange={addBlock}
              options={available.map((def) => ({
                value: def.id,
                label: def.label,
              }))}
              placeholder="Select filter"
              variant="ghost"
              defaultOpen
              className="h-6 w-full"
              testId="automation-filter-select"
            />
          </BlockHeader>
        </div>
      )}

      <Button
        type="button"
        variant="outline"
        size="sm"
        className="h-8 border-dashed bg-soft-background"
        disabled={isPicking || available.length === 0}
        onClick={() => setIsPicking(true)}
      >
        <Plus className="mr-1 size-3.5" />
        Add filter
      </Button>
    </div>
  );
};

export default AutomationFilterBlocks;
