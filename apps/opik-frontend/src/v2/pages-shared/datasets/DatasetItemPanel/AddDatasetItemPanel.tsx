import React, { useEffect, useMemo, useState } from "react";

import { Button } from "@/ui/button";
import Loader from "@/shared/Loader/Loader";
import { DATASET_ITEM_SOURCE, DatasetItemColumn } from "@/types/datasets";
import { useAddItem } from "@/store/TestSuiteDraftStore";
import { prepareFormDataForSave } from "@/v2/pages-shared/datasets/DatasetItemEditor/hooks/useDatasetItemFormHelpers";
import DatasetFieldsEditor, {
  FieldFocus,
} from "@/v2/pages-shared/datasets/DatasetItemFields/DatasetFieldsEditor";
import {
  columnsToFieldRows,
  DatasetFieldRow,
  fieldRowsToData,
  getFieldRowErrors,
} from "@/v2/pages-shared/datasets/DatasetItemFields/datasetFieldRows";
import AddItemPanelWrapper, {
  AddItemPanelContext,
} from "./AddItemPanelWrapper";

type AddDatasetItemFormProps = AddItemPanelContext & {
  columns: DatasetItemColumn[];
  onClose: () => void;
};

const AddDatasetItemForm: React.FC<AddDatasetItemFormProps> = ({
  columns,
  tags,
  setHasUnsavedChanges,
  requestClose,
  onClose,
}) => {
  const addItem = useAddItem();
  const [rows, setRows] = useState<DatasetFieldRow[]>(() =>
    columnsToFieldRows(columns),
  );
  const [focus, setFocus] = useState<FieldFocus>(() =>
    rows.length > 0 ? { id: rows[0].id, target: "value" } : null,
  );

  const errors = useMemo(() => getFieldRowErrors(rows), [rows]);
  const hasErrors = Object.keys(errors).length > 0;
  const fieldCount = rows.length;
  const canSubmit = fieldCount > 0 && !hasErrors;

  const status = hasErrors
    ? "Fix the field names to add this record"
    : fieldCount === 0
      ? "Add at least one field"
      : `Adding ${fieldCount} field${fieldCount === 1 ? "" : "s"}`;

  const isDirty =
    rows.length !== columns.length ||
    rows.some((row) => row.value.trim() !== "");
  useEffect(() => {
    setHasUnsavedChanges(isDirty);
  }, [isDirty, setHasUnsavedChanges]);

  const handleSubmit = () => {
    if (!canSubmit) return;

    const now = new Date().toISOString();
    addItem({
      data: prepareFormDataForSave(fieldRowsToData(rows), columns),
      source: DATASET_ITEM_SOURCE.manual,
      tags,
      created_at: now,
      last_updated_at: now,
    });
    onClose();
  };

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex-1 overflow-y-auto p-6">
        <DatasetFieldsEditor
          rows={rows}
          focus={focus}
          onRowsChange={setRows}
          onFocusChange={setFocus}
        />
      </div>

      <div className="flex shrink-0 flex-wrap items-center gap-2 border-t px-6 py-4">
        <span className="comet-body-xs min-w-48 flex-1 text-muted-slate">
          {status}
        </span>
        <div className="ml-auto flex gap-2">
          <Button variant="outline" size="sm" onClick={requestClose}>
            Cancel
          </Button>
          <Button
            variant="default"
            size="sm"
            disabled={!canSubmit}
            onClick={handleSubmit}
          >
            Add record
          </Button>
        </div>
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
    showAddTagLabel
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
