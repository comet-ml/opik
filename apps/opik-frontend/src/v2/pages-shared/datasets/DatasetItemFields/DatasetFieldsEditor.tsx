import React, { useMemo } from "react";
import { Plus } from "lucide-react";

import { Button } from "@/ui/button";
import DatasetFieldRow from "./DatasetFieldRow";
import {
  createFieldRow,
  DatasetFieldRow as DatasetFieldRowType,
  getFieldRowErrors,
} from "./datasetFieldRows";

export type FieldFocus = { id: string; target: "name" | "value" } | null;

type DatasetFieldsEditorProps = {
  rows: DatasetFieldRowType[];
  focus: FieldFocus;
  removeTooltip: string;
  onRowsChange: (rows: DatasetFieldRowType[]) => void;
  onFocusChange: (focus: FieldFocus) => void;
};

const DatasetFieldsEditor: React.FunctionComponent<
  DatasetFieldsEditorProps
> = ({ rows, focus, removeTooltip, onRowsChange, onFocusChange }) => {
  const errors = useMemo(() => getFieldRowErrors(rows), [rows]);

  const handleAddField = () => {
    const row = createFieldRow();
    onRowsChange([...rows, row]);
    onFocusChange({ id: row.id, target: "name" });
  };

  return (
    <div className="flex flex-col gap-2">
      {rows.map((row) => (
        <DatasetFieldRow
          key={row.id}
          row={row}
          error={errors[row.id]}
          autoFocus={focus?.id === row.id ? focus.target : undefined}
          removeTooltip={removeTooltip}
          onChange={(next) =>
            onRowsChange(rows.map((r) => (r.id === row.id ? next : r)))
          }
          onRemove={() => onRowsChange(rows.filter((r) => r.id !== row.id))}
        />
      ))}
      <div>
        <Button variant="ghost" size="sm" onClick={handleAddField}>
          <Plus className="mr-1.5 size-3.5" />
          Add field
        </Button>
      </div>
    </div>
  );
};

export default DatasetFieldsEditor;
