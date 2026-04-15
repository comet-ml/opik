import React, { useCallback } from "react";

import { DATASET_ITEM_SOURCE, DatasetItemColumn } from "@/types/datasets";
import { useAddItem } from "@/store/TestSuiteDraftStore";
import { useDatasetItemData } from "@/v2/pages-shared/datasets/DatasetItemEditor/hooks/useDatasetItemData";
import { prepareFormDataForSave } from "@/v2/pages-shared/datasets/DatasetItemEditor/hooks/useDatasetItemFormHelpers";
import DatasetItemEditorForm from "@/v2/pages-shared/datasets/DatasetItemEditor/DatasetItemEditorForm";
import AddItemPanelWrapper from "./AddItemPanelWrapper";

const ADD_DATASET_ITEM_FORM_ID = "add-dataset-item-form";

interface DatasetItemFormContentProps {
  columns: DatasetItemColumn[];
  tags: string[];
  setHasUnsavedChanges: (v: boolean) => void;
  onClose: () => void;
}

const DatasetItemFormContent: React.FC<DatasetItemFormContentProps> = ({
  columns,
  tags,
  setHasUnsavedChanges,
  onClose,
}) => {
  const addItem = useAddItem();
  const { fields } = useDatasetItemData({
    datasetItemId: undefined,
    columns,
  });

  const handleSave = useCallback(
    (data: Record<string, unknown>) => {
      const preparedData = prepareFormDataForSave(data, columns);
      const now = new Date().toISOString();
      addItem({
        data: preparedData,
        source: DATASET_ITEM_SOURCE.manual,
        tags,
        created_at: now,
        last_updated_at: now,
      });
      onClose();
    },
    [addItem, onClose, columns, tags],
  );

  return (
    <div className="p-6">
      <DatasetItemEditorForm
        formId={ADD_DATASET_ITEM_FORM_ID}
        fields={fields}
        onSubmit={handleSave}
        setHasUnsavedChanges={setHasUnsavedChanges}
      />
    </div>
  );
};

interface AddDatasetItemPanelProps {
  open: boolean;
  onClose: () => void;
  columns: DatasetItemColumn[];
}

const AddDatasetItemPanel: React.FC<AddDatasetItemPanelProps> = ({
  open,
  onClose,
  columns,
}) => (
  <AddItemPanelWrapper
    panelId="dataset-item-panel"
    formId={ADD_DATASET_ITEM_FORM_ID}
    open={open}
    onClose={onClose}
    initialWidth={0.4}
  >
    {({ tags, setHasUnsavedChanges }) => (
      <DatasetItemFormContent
        columns={columns}
        tags={tags}
        setHasUnsavedChanges={setHasUnsavedChanges}
        onClose={onClose}
      />
    )}
  </AddItemPanelWrapper>
);

export default AddDatasetItemPanel;
