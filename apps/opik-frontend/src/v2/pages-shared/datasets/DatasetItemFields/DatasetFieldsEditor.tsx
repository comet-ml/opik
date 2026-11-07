import React, { useMemo } from "react";
import { Plus, Workflow } from "lucide-react";

import { Tag } from "@/ui/tag";
import OpikLeaf from "@/icons/opik-leaf.svg?react";
import OpikLeafDark from "@/icons/opik-leaf-dark.svg?react";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import DatasetFieldRow from "./DatasetFieldRow";
import {
  createFieldRow,
  DatasetFieldRow as DatasetFieldRowType,
  getFieldRowErrors,
} from "./datasetFieldRows";

export type FieldFocus = { id: string; target: "name" | "value" } | null;

const STARTER_FIELDS = [
  { name: "input", label: "Input" },
  { name: "expected_output", label: "Expected output" },
  { name: "metadata", label: "Metadata" },
];

type DatasetFieldsEditorProps = {
  rows: DatasetFieldRowType[];
  focus: FieldFocus;
  onRowsChange: (rows: DatasetFieldRowType[]) => void;
  onFocusChange: (focus: FieldFocus) => void;
};

const DatasetFieldsEditor: React.FunctionComponent<
  DatasetFieldsEditorProps
> = ({ rows, focus, onRowsChange, onFocusChange }) => {
  const { themeMode } = useTheme();
  const LeafIcon = themeMode === THEME_MODE.DARK ? OpikLeafDark : OpikLeaf;
  const errors = useMemo(() => getFieldRowErrors(rows), [rows]);

  const addRow = (name: string, target: "name" | "value") => {
    const row = createFieldRow(name);
    onRowsChange([...rows, row]);
    onFocusChange({ id: row.id, target });
  };

  const addFieldButton = (
    <button
      type="button"
      className="comet-body-xs flex h-8 w-full items-center justify-center gap-1 rounded-md border border-dashed border-border bg-soft-background text-foreground hover:bg-primary-foreground"
      onClick={() => addRow("", "name")}
    >
      <Plus className="size-3" />
      Add field
    </button>
  );

  if (rows.length === 0) {
    return (
      <div className="flex flex-col gap-4">
        <div
          data-testid="add-record-starter"
          className="flex flex-col items-center gap-4 rounded-md border border-border bg-background px-4 pb-6 pt-4"
        >
          <LeafIcon className="size-8" />
          <div className="flex flex-col items-center gap-1 text-center">
            <span className="comet-body-xs-accented">No fields defined</span>
            <span className="comet-body-xs">
              Start with one of the usual fields or create custom ones
            </span>
          </div>
          <div className="flex flex-wrap justify-center gap-2">
            {STARTER_FIELDS.map((field) => (
              <button
                key={field.name}
                type="button"
                className="flex"
                onClick={() => addRow(field.name, "value")}
              >
                <Tag variant="blue" className="flex items-center gap-1">
                  <Plus className="size-3" />
                  <span>{field.label}</span>
                </Tag>
              </button>
            ))}
          </div>
        </div>
        {addFieldButton}
      </div>
    );
  }

  return (
    <div className="overflow-hidden rounded-md border border-border bg-soft-background">
      <div className="flex flex-col gap-1.5 border-b border-border p-3">
        <div className="flex items-center gap-1.5">
          <div className="flex size-5 shrink-0 items-center justify-center rounded-md bg-[var(--upload-chip-icon-bg)]">
            <Workflow className="size-3 text-foreground" />
          </div>
          <span className="comet-body-s-accented">Item fields</span>
        </div>
        <p className="comet-body-xs text-muted-slate">
          Define fields and values. Fields without a value will be saved as
          empty.
        </p>
      </div>
      <div className="flex flex-col gap-3 p-2">
        <div className="flex flex-col gap-1.5">
          {rows.map((row) => (
            <DatasetFieldRow
              key={row.id}
              row={row}
              error={errors[row.id]}
              autoFocus={focus?.id === row.id ? focus.target : undefined}
              onChange={(next) =>
                onRowsChange(rows.map((r) => (r.id === row.id ? next : r)))
              }
              onRemove={() => onRowsChange(rows.filter((r) => r.id !== row.id))}
            />
          ))}
        </div>
        {addFieldButton}
      </div>
    </div>
  );
};

export default DatasetFieldsEditor;
