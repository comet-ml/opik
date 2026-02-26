import { useCallback, type ChangeEvent } from "react";
import TextareaAutosize from "react-textarea-autosize";
import { useDatasetItemEditorAutosaveContext } from "@/components/pages-shared/datasets/DatasetItemEditor/DatasetItemEditorAutosaveContext";
import { useEditItem } from "@/store/EvaluationSuiteDraftStore";
import { TEXT_AREA_CLASSES } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";

interface ItemDescriptionSectionProps {
  itemId: string;
}

function ItemDescriptionSection({ itemId }: ItemDescriptionSectionProps) {
  const { datasetItem } = useDatasetItemEditorAutosaveContext();
  const editItem = useEditItem();

  const description = datasetItem?.description ?? "";

  const handleChange = useCallback(
    (e: ChangeEvent<HTMLTextAreaElement>) => {
      editItem(itemId, { description: e.target.value });
    },
    [itemId, editItem],
  );

  return (
    <div>
      <h3 className="comet-body-s-accented mb-2">Description</h3>
      <TextareaAutosize
        value={description}
        onChange={handleChange}
        placeholder="Describe this item..."
        className={cn(TEXT_AREA_CLASSES, "min-h-0 resize-none")}
        minRows={1}
      />
    </div>
  );
}

export default ItemDescriptionSection;
