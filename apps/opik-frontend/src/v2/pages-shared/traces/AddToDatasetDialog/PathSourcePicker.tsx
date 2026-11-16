import React, { useState } from "react";
import { ChevronDown } from "lucide-react";

import { Button } from "@/ui/button";
import JsonTreePopover from "@/shared/JsonTreePopover/JsonTreePopover";
import { JsonObject } from "@/types/shared";
import { cn } from "@/lib/utils";

type PathSourcePickerProps = {
  fieldName?: string;
  path?: string;
  placeholder?: string;
  badge?: React.ReactNode;
  invalid?: boolean;
  treeData: JsonObject;
  isPending?: boolean;
  entityLabel?: string;
  trigger?: React.ReactNode;
  defaultOpen?: boolean;
  sideOffset?: number;
  onSelect: (path: string) => void;
  onClear?: () => void;
};

const PathSourcePicker: React.FunctionComponent<PathSourcePickerProps> = ({
  fieldName,
  path,
  placeholder = "Select a field",
  badge,
  invalid,
  treeData,
  isPending,
  entityLabel = "traces",
  trigger,
  defaultOpen = false,
  sideOffset,
  onSelect,
  onClear,
}) => {
  const [open, setOpen] = useState(defaultOpen);
  const name = fieldName?.trim();

  const defaultTrigger = (
    <button
      type="button"
      data-testid="path-source-trigger"
      className={cn(
        "flex h-6 w-full items-center gap-1 rounded-md border border-border bg-background px-2 text-left hover:border-slate-300",
        invalid && "border-destructive",
      )}
    >
      <span
        className={cn(
          "comet-code min-w-0 flex-1 truncate text-xs",
          path ? "text-foreground" : "text-light-slate",
        )}
      >
        {path || placeholder}
      </span>
      {badge}
      <ChevronDown className="size-3 shrink-0 text-muted-slate" />
    </button>
  );

  const header = (
    <div className="flex items-center gap-2 border-b px-3 py-2">
      <span className="comet-body-xs-accented min-w-0 flex-1 truncate">
        {name ? `Select a field to map to: ${name}` : "Add field"}
      </span>
      {onClear && path && (
        <Button
          variant="link"
          size="3xs"
          className="shrink-0 px-0"
          onClick={() => {
            onClear();
            setOpen(false);
          }}
        >
          Leave unmapped
        </Button>
      )}
    </div>
  );

  return (
    <JsonTreePopover
      data={treeData}
      open={open}
      onOpenChange={setOpen}
      onSelect={(selectedPath) => onSelect(selectedPath)}
      trigger={trigger ?? defaultTrigger}
      selectedPath={path}
      emptyState={
        isPending
          ? `Loading fields from the selected ${entityLabel}...`
          : `No fields to show. The sampled ${entityLabel} could not be loaded.`
      }
      contentClassName="w-[var(--radix-popover-trigger-width)] min-w-0 max-w-none"
      sideOffset={sideOffset}
      header={header}
    />
  );
};

export default PathSourcePicker;
