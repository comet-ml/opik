import React, { useCallback } from "react";
import TextareaAutosize from "react-textarea-autosize";
import { useDatasetItemEditorAutosaveContext } from "@/components/pages/DatasetItemsPage/DatasetItemEditor/DatasetItemEditorAutosaveContext";

interface ItemDescriptionSectionProps {
  itemId: string;
}

const ItemDescriptionSection: React.FC<ItemDescriptionSectionProps> = ({
  itemId,
}) => {
  const { datasetItem, handleFieldChange, fields } =
    useDatasetItemEditorAutosaveContext();

  const description =
    (datasetItem?.data as Record<string, unknown> | undefined)?.description ??
    "";

  const handleChange = useCallback(
    (value: string) => {
      if (!itemId) return;

      const currentData: Record<string, unknown> = {};
      for (const f of fields) {
        currentData[f.key] = f.value;
      }
      currentData["description"] = value;
      handleFieldChange(currentData);
    },
    [itemId, fields, handleFieldChange],
  );

  return (
    <div>
      <h3 className="comet-body-s-accented mb-2">Description</h3>
      <TextareaAutosize
        value={String(description)}
        onChange={(e) => handleChange(e.target.value)}
        placeholder="Describe this item..."
        className="flex w-full rounded-md resize-none border border-border bg-background px-3 py-2 text-sm text-foreground ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50 hover:shadow-sm focus-visible:border-primary"
        minRows={2}
      />
    </div>
  );
};

export default ItemDescriptionSection;
