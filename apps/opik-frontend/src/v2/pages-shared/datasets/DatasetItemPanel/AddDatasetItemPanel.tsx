import React, { useEffect, useMemo, useState } from "react";

import { Button } from "@/ui/button";
import Loader from "@/shared/Loader/Loader";
import { HotkeyDisplay } from "@/ui/hotkey-display";
import { Tag } from "@/ui/tag";
import { useToast } from "@/ui/use-toast";
import { isMac } from "@/lib/utils";
import { DATASET_ITEM_SOURCE, DatasetItemColumn } from "@/types/datasets";
import { useAddItem } from "@/store/TestSuiteDraftStore";
import { prepareFormDataForSave } from "@/v2/pages-shared/datasets/DatasetItemEditor/hooks/useDatasetItemFormHelpers";
import DatasetFieldsEditor, {
  FieldFocus,
} from "@/v2/pages-shared/datasets/DatasetItemFields/DatasetFieldsEditor";
import {
  columnsToFieldRows,
  countFilledFields,
  createFieldRow,
  DatasetFieldRow,
  fieldRowsToData,
  getFieldRowErrors,
} from "@/v2/pages-shared/datasets/DatasetItemFields/datasetFieldRows";
import AddItemPanelWrapper, {
  AddItemPanelContext,
} from "./AddItemPanelWrapper";

const STARTER_FIELDS = [
  { name: "input", label: "Input" },
  { name: "expected_output", label: "Expected output" },
  { name: "metadata", label: "Metadata" },
];

const SUBMIT_HOTKEY = isMac ? "⌘↵" : "Ctrl+↵";

type AddDatasetItemFormProps = AddItemPanelContext & {
  columns: DatasetItemColumn[];
  onClose: () => void;
};

const AddDatasetItemForm: React.FC<AddDatasetItemFormProps> = ({
  columns,
  tags,
  setHasUnsavedChanges,
  resetTags,
  requestClose,
  onClose,
}) => {
  const addItem = useAddItem();
  const { toast } = useToast();
  const [rows, setRows] = useState<DatasetFieldRow[]>(() =>
    columnsToFieldRows(columns),
  );
  const [focus, setFocus] = useState<FieldFocus>(() =>
    rows.length > 0 ? { id: rows[0].id, target: "value" } : null,
  );

  const errors = useMemo(() => getFieldRowErrors(rows), [rows]);
  const hasErrors = Object.keys(errors).length > 0;
  const filledCount = countFilledFields(rows, errors);
  const canSubmit = filledCount > 0 && !hasErrors;

  const disabledReason = hasErrors
    ? "Fix the field names above to add this record."
    : filledCount === 0
      ? "Enter a value in at least one field to add this record."
      : undefined;

  const isDirty = rows.some((row) => row.value.trim() !== "");
  useEffect(() => {
    setHasUnsavedChanges(isDirty);
  }, [isDirty, setHasUnsavedChanges]);

  const handleStarterField = (name: string) => {
    const row = createFieldRow(name);
    setRows((prev) => [...prev, row]);
    setFocus({ id: row.id, target: "value" });
  };

  const submit = (addAnother: boolean) => {
    if (!canSubmit) return;

    const now = new Date().toISOString();
    addItem({
      data: prepareFormDataForSave(
        fieldRowsToData(rows, { dropEmpty: true }),
        columns,
      ),
      source: DATASET_ITEM_SOURCE.manual,
      tags,
      created_at: now,
      last_updated_at: now,
    });

    if (!addAnother) {
      onClose();
      return;
    }

    // Keep the field set the user just built so the next record starts from it.
    const nextRows = rows.map((row) => createFieldRow(row.name.trim()));
    setRows(nextRows);
    setFocus(
      nextRows.length > 0 ? { id: nextRows[0].id, target: "value" } : null,
    );
    resetTags();
    toast({ description: "Record added to your draft changes" });
  };

  const handleKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
      event.preventDefault();
      submit(false);
    }
  };

  const showStarter = rows.length === 0;

  return (
    <div className="flex min-h-0 flex-1 flex-col" onKeyDown={handleKeyDown}>
      <div className="flex-1 overflow-y-auto p-6">
        {showStarter && (
          <div
            data-testid="add-record-starter"
            className="mb-4 rounded-md border border-border bg-muted/50 p-4"
          >
            <div className="comet-body-s-accented mb-1">
              Start with a few fields
            </div>
            <p className="comet-body-s mb-3 text-muted-slate">
              A record is a set of named fields. Pick a common one or add your
              own — fields you add become columns for this dataset.
            </p>
            <div className="flex flex-wrap gap-2">
              {STARTER_FIELDS.map((field) => (
                <button
                  key={field.name}
                  type="button"
                  className="flex"
                  onClick={() => handleStarterField(field.name)}
                >
                  <Tag variant="gray" className="text-foreground">
                    {field.label}
                  </Tag>
                </button>
              ))}
            </div>
          </div>
        )}
        <DatasetFieldsEditor
          rows={rows}
          focus={focus}
          removeTooltip="Remove field from this record"
          onRowsChange={setRows}
          onFocusChange={setFocus}
        />
      </div>

      <div className="flex shrink-0 items-center gap-2 border-t px-6 py-4">
        <span className="comet-body-xs mr-auto text-muted-slate">
          {disabledReason ??
            `${filledCount} of ${rows.length} field${
              rows.length === 1 ? "" : "s"
            } filled`}
        </span>
        <Button variant="outline" size="sm" onClick={requestClose}>
          Cancel
        </Button>
        <Button
          variant="outline"
          size="sm"
          disabled={!canSubmit}
          onClick={() => submit(true)}
        >
          Save & add another
        </Button>
        <Button
          variant="default"
          size="sm"
          disabled={!canSubmit}
          onClick={() => submit(false)}
        >
          Add record
          <HotkeyDisplay hotkey={SUBMIT_HOTKEY} size="xs" className="ml-2" />
        </Button>
      </div>
    </div>
  );
};

interface AddDatasetItemPanelProps {
  open: boolean;
  onClose: () => void;
  columns: DatasetItemColumn[];
  isColumnsLoading?: boolean;
}

const AddDatasetItemPanel: React.FC<AddDatasetItemPanelProps> = ({
  open,
  onClose,
  columns,
  isColumnsLoading = false,
}) => (
  <AddItemPanelWrapper
    panelId="dataset-item-panel"
    title="Add record"
    open={open}
    onClose={onClose}
    initialWidth={0.4}
  >
    {(context) =>
      isColumnsLoading ? (
        <div className="flex flex-1 items-center justify-center">
          <Loader />
        </div>
      ) : (
        <AddDatasetItemForm {...context} columns={columns} onClose={onClose} />
      )
    }
  </AddItemPanelWrapper>
);

export default AddDatasetItemPanel;
