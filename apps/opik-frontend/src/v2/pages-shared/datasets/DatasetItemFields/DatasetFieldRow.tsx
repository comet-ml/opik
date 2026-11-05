import React, { useState } from "react";
import TextareaAutosize from "react-textarea-autosize";
import { Pencil, X } from "lucide-react";

import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
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
  removeTooltip: string;
  onChange: (row: DatasetFieldRowType) => void;
  onRemove: () => void;
};

const DatasetFieldRow: React.FunctionComponent<DatasetFieldRowProps> = ({
  row,
  error,
  autoFocus,
  removeTooltip,
  onChange,
  onRemove,
}) => {
  const [isEditingName, setIsEditingName] = useState(autoFocus === "name");
  const showNameInput = isEditingName || Boolean(error);
  const invalidJson = isInvalidJsonValue(row);

  return (
    <div data-testid="dataset-field-row" className="flex gap-1">
      <div className="min-w-0 flex-1 rounded-md border border-border p-2">
        <div className="mb-1.5 flex h-6 items-center">
          {showNameInput ? (
            <Input
              dimension="xs"
              value={row.name}
              placeholder="Field name"
              aria-label="Field name"
              className={cn("px-2", error && "border-destructive")}
              autoFocus={isEditingName}
              onChange={(event) =>
                onChange({ ...row, name: event.target.value })
              }
              onBlur={() => setIsEditingName(false)}
              onKeyDown={(event) => {
                if (event.key === "Enter" && !event.metaKey && !event.ctrlKey) {
                  event.preventDefault();
                  setIsEditingName(false);
                }
              }}
            />
          ) : (
            <TooltipWrapper content="Rename field">
              <button
                type="button"
                className="group flex min-w-0 items-center gap-1.5 rounded px-0.5 text-left"
                onClick={() => setIsEditingName(true)}
              >
                <span className="comet-body-s-accented truncate text-foreground">
                  {row.name}
                </span>
                <Pencil className="size-3 shrink-0 text-muted-slate opacity-0 group-hover:opacity-100 group-focus-visible:opacity-100" />
              </button>
            </TooltipWrapper>
          )}
        </div>
        <TextareaAutosize
          value={row.value}
          aria-label={row.name ? `${row.name} value` : "Field value"}
          placeholder={row.isJson ? "Enter a JSON value…" : "Enter a value…"}
          autoFocus={autoFocus === "value"}
          onChange={(event) => onChange({ ...row, value: event.target.value })}
          minRows={1}
          maxRows={16}
          className={cn(
            "flex w-full resize-none rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground hover:shadow-sm focus-visible:border-primary focus-visible:outline-none",
            row.isJson && "font-mono",
          )}
        />
        {error && (
          <p className="comet-body-xs mt-1 text-destructive">
            {FIELD_ROW_ERROR_TEXT[error]}
          </p>
        )}
        {!error && invalidJson && (
          <p className="comet-body-xs mt-1 text-muted-slate">
            Not valid JSON — it will be saved as text.
          </p>
        )}
      </div>
      <TooltipWrapper content={removeTooltip}>
        <Button
          variant="minimal"
          size="icon-2xs"
          className="mt-2 shrink-0"
          aria-label={`Remove ${row.name || "field"}`}
          onClick={onRemove}
        >
          <X />
        </Button>
      </TooltipWrapper>
    </div>
  );
};

export default DatasetFieldRow;
