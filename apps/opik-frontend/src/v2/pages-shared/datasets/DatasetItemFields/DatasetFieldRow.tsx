import React from "react";
import TextareaAutosize from "react-textarea-autosize";

import { Input } from "@/ui/input";
import FieldRowShell from "@/v2/components/FieldRowShell/FieldRowShell";
import { cn } from "@/lib/utils";
import {
  DatasetFieldRow as DatasetFieldRowType,
  DatasetFieldRowError,
  FIELD_ROW_ERROR_TEXT,
  isInvalidJsonValue,
} from "./datasetFieldRows";

type DatasetFieldRowProps = {
  row: DatasetFieldRowType;
  error?: DatasetFieldRowError;
  autoFocus?: "name" | "value";
  onChange: (row: DatasetFieldRowType) => void;
  onRemove: () => void;
};

const DatasetFieldRow: React.FunctionComponent<DatasetFieldRowProps> = ({
  row,
  error,
  autoFocus,
  onChange,
  onRemove,
}) => {
  const invalidJson = !error && isInvalidJsonValue(row);

  return (
    <FieldRowShell
      data-testid="dataset-field-row"
      removeLabel={`Remove ${row.name || "field"}`}
      removeTooltip="Remove field from this record"
      onRemove={onRemove}
      name={
        <Input
          dimension="xs"
          value={row.name}
          placeholder="Field name"
          aria-label="Field name"
          className={cn("px-2", error && "border-destructive")}
          autoFocus={autoFocus === "name"}
          onChange={(event) => onChange({ ...row, name: event.target.value })}
        />
      }
      value={
        <TextareaAutosize
          value={row.value}
          aria-label={row.name ? `${row.name} value` : "Field value"}
          placeholder="Add value"
          autoFocus={autoFocus === "value"}
          onChange={(event) => onChange({ ...row, value: event.target.value })}
          minRows={1}
          maxRows={4}
          className="flex min-h-6 w-full resize-none rounded-md border border-border bg-background px-2 py-1 font-mono text-xs leading-4 text-foreground placeholder:text-light-slate focus-visible:border-primary focus-visible:outline-none"
        />
      }
      message={
        (error || invalidJson) && (
          <p
            className={cn(
              "comet-body-xs mt-1",
              error ? "text-destructive" : "text-muted-slate",
            )}
          >
            {error
              ? FIELD_ROW_ERROR_TEXT[error]
              : "Not valid JSON — it will be saved as text."}
          </p>
        )
      }
    />
  );
};

export default DatasetFieldRow;
