import React, { useState } from "react";
import { Plus, Workflow } from "lucide-react";

import { Button } from "@/ui/button";
import { Label } from "@/ui/label";
import { Switch } from "@/ui/switch";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { JsonObject } from "@/types/shared";
import { FieldCoverage } from "./useFieldCoverage";
import MappingRow from "./MappingRow";
import QuickAddChips from "./QuickAddChips";
import PathSourcePicker from "./PathSourcePicker";
import useFieldMappings from "./useFieldMappings";

const ADD_FIELD_TRIGGER_HEIGHT = 32;

const EXISTING_FIELD_TOOLTIP =
  "This field belongs to the dataset. You can leave it unmapped, but not remove it.";

type FieldMappingSectionProps = {
  advanced: boolean;
  setAdvanced: (advanced: boolean) => void;
  treeData: JsonObject;
  isPendingTree: boolean;
  hasOnlySpans: boolean;
  coverage: Record<string, FieldCoverage>;
  datasetColumns: string[];
  datasetItemCount: number;
  mappings: ReturnType<typeof useFieldMappings>;
};

const FieldMappingSection: React.FunctionComponent<
  FieldMappingSectionProps
> = ({
  advanced,
  setAdvanced,
  treeData,
  isPendingTree,
  hasOnlySpans,
  coverage,
  datasetColumns,
  datasetItemCount,
  mappings,
}) => {
  const {
    fixedRows,
    additionalRows,
    availableChips,
    rowErrors,
    setFixedPath,
    addRow,
    setExistingPath,
    removeRow,
    renameRow,
    setRowPath,
    setManagedOption,
    remapManagedRow,
  } = mappings;

  const entityLabel = hasOnlySpans ? "spans" : "traces";

  const [focusNameRowId, setFocusNameRowId] = useState<string | null>(null);

  const existingColumns = new Set(datasetColumns);

  return (
    <div className="mb-4 overflow-hidden rounded-md border border-border bg-soft-background">
      <div className="flex flex-col gap-1.5 border-b border-border p-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5">
            <div className="flex size-5 shrink-0 items-center justify-center rounded-md bg-[var(--upload-chip-icon-bg)]">
              <Workflow className="size-3 text-foreground" />
            </div>
            <Label
              htmlFor="advanced-mapping"
              className="comet-body-s-accented cursor-pointer"
            >
              Advanced mapping
            </Label>
          </div>
          <TooltipWrapper
            content={
              advanced
                ? "Advanced mapping stays on once it's turned on. Close the dialog to start again in basic mode."
                : undefined
            }
          >
            <div>
              <Switch
                id="advanced-mapping"
                size="xs"
                checked={advanced}
                disabled={advanced}
                onCheckedChange={setAdvanced}
              />
            </div>
          </TooltipWrapper>
        </div>
        <p className="comet-body-xs text-muted-slate">
          Choose which {hasOnlySpans ? "span" : "trace"} field goes into each
          column.
        </p>
      </div>

      {advanced && (
        <div className="flex flex-col gap-3 p-2">
          <div className="flex flex-col gap-1.5">
            {fixedRows.map((row) => (
              <MappingRow
                key={row.id}
                row={row}
                treeData={treeData}
                coverage={coverage[row.id]}
                rowError={rowErrors[row.id]}
                isPending={isPendingTree}
                entityLabel={entityLabel}
                onPathSelect={(path) => setFixedPath(row.id, path)}
              />
            ))}
          </div>

          <div className="flex flex-col gap-1.5">
            <span className="comet-body-xs-accented">Additional fields</span>
            {additionalRows.map((row) =>
              row.kind === "existing" ? (
                <MappingRow
                  key={row.id}
                  row={row}
                  treeData={treeData}
                  coverage={coverage[row.id]}
                  isPending={isPendingTree}
                  entityLabel={entityLabel}
                  onPathSelect={(path) => setExistingPath(row.name, path)}
                  onPathClear={() => setExistingPath(row.name)}
                  removeDisabledReason={EXISTING_FIELD_TOOLTIP}
                />
              ) : row.kind === "managed" ? (
                <MappingRow
                  key={row.id}
                  row={row}
                  treeData={treeData}
                  coverage={coverage[row.id]}
                  isPending={isPendingTree}
                  entityLabel={entityLabel}
                  onPathSelect={(path) => {
                    if (path !== row.name) remapManagedRow(row.option!, path);
                  }}
                  onRemove={() => setManagedOption(row.option!, false)}
                />
              ) : (
                <MappingRow
                  key={row.id}
                  row={row}
                  treeData={treeData}
                  coverage={coverage[row.id]}
                  rowError={
                    row.touched || rowErrors[row.id] === "no_path"
                      ? rowErrors[row.id]
                      : undefined
                  }
                  newColumnItemCount={
                    existingColumns.has(row.name.trim())
                      ? undefined
                      : datasetItemCount
                  }
                  isPending={isPendingTree}
                  entityLabel={entityLabel}
                  autoFocusName={row.id === focusNameRowId}
                  onRename={(name) => renameRow(row.id, name)}
                  onPathSelect={(path) => setRowPath(row.id, path)}
                  onRemove={() => removeRow(row.id)}
                />
              ),
            )}
            <PathSourcePicker
              treeData={treeData}
              isPending={isPendingTree}
              entityLabel={entityLabel}
              sideOffset={-ADD_FIELD_TRIGGER_HEIGHT}
              onSelect={(path) => setFocusNameRowId(addRow(path))}
              trigger={
                <div className="flex h-8 cursor-pointer items-center justify-center rounded-md border border-dashed border-border bg-soft-background data-[state=open]:invisible">
                  <Button variant="ghost" size="2xs">
                    <Plus className="size-3" />
                    Add field
                  </Button>
                </div>
              }
            />
          </div>

          <QuickAddChips
            chips={availableChips}
            onAdd={(option) => setManagedOption(option, true)}
          />
        </div>
      )}
    </div>
  );
};

export default FieldMappingSection;
