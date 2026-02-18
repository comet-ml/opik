import React from "react";
import { useDatasetItemEditorAutosaveContext } from "@/components/pages/DatasetItemsPage/DatasetItemEditor/DatasetItemEditorAutosaveContext";
import DatasetItemEditorForm from "@/components/pages/DatasetItemsPage/DatasetItemEditor/DatasetItemEditorForm";

const ItemContextSection: React.FC = () => {
  const { fields, formId, handleFieldChange } =
    useDatasetItemEditorAutosaveContext();

  return (
    <div>
      <h3 className="comet-body-s-accented mb-2">Context</h3>
      <DatasetItemEditorForm
        formId={formId}
        fields={fields}
        onFieldChange={handleFieldChange}
      />
    </div>
  );
};

export default ItemContextSection;
