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

type FieldMappingSectionProps = {
  advanced: boolean;
  setAdvanced: (advanced: boolean) => void;
  treeData: JsonObject;
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
    datasetChips,
    rowErrors,
    setFixedPath,
    addRow,
    addNamedRow,
    removeRow,
    renameRow,
    setRowPath,
    setManagedOption,
    canUseBasicMode,
  } = mappings;

  const lockedInAdvanced = advanced && !canUseBasicMode;

  const [focusNameRowId, setFocusNameRowId] = useState<string | null>(null);
  const [openSourceRowId, setOpenSourceRowId] = useState<string | null>(null);

  const existingColumns = new Set(datasetColumns);
  const addFromDataset = (column: string) =>
    setOpenSourceRowId(addNamedRow(column));

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
              lockedInAdvanced
                ? "Basic mode can't represent this mapping. Reset the fields to their defaults to switch back."
                : undefined
            }
          >
            <div>
              <Switch
                id="advanced-mapping"
                size="xs"
                checked={advanced}
                disabled={lockedInAdvanced}
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
                onPathSelect={(path) => setFixedPath(row.id, path)}
              />
            ))}
          </div>

          <div className="flex flex-col gap-1.5">
            <span className="comet-body-xs-accented">Additional fields</span>
            {additionalRows.map((row) =>
              row.kind === "managed" ? (
                <MappingRow
                  key={row.id}
                  row={row}
                  treeData={treeData}
                  coverage={coverage[row.id]}
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
                  autoFocusName={row.id === focusNameRowId}
                  autoOpenSource={row.id === openSourceRowId}
                  onRename={(name) => renameRow(row.id, name)}
                  onPathSelect={(path) => setRowPath(row.id, path)}
                  onRemove={() => removeRow(row.id)}
                />
              ),
            )}
            <PathSourcePicker
              treeData={treeData}
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
            managedChips={availableChips}
            datasetChips={datasetChips}
            onAddManaged={(option) => setManagedOption(option, true)}
            onAddDatasetColumn={addFromDataset}
          />
        </div>
      )}
    </div>
  );
};

export default FieldMappingSection;
