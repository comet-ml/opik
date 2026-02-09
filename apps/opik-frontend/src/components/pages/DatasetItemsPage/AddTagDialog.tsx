import React, { useCallback } from "react";
import { DatasetItem } from "@/types/datasets";
import useDatasetItemBatchUpdateMutation from "@/api/datasets/useDatasetItemBatchUpdateMutation";
import { Filters } from "@/types/filters";
import {
  useBulkEditItems,
  useIsAllItemsSelected,
} from "@/store/DatasetDraftStore";
import { generateBatchGroupId } from "@/lib/utils";
import ManageTagsDialog from "@/components/shared/ManageTagsDialog/ManageTagsDialog";

type AddTagDialogProps = {
  datasetId: string;
  rows: Array<DatasetItem>;
  open: boolean;
  setOpen: (open: boolean) => void;
  onSuccess?: () => void;
  filters?: Filters;
  search?: string;
  totalCount?: number;
};

const AddTagDialog: React.FunctionComponent<AddTagDialogProps> = ({
  datasetId,
  rows,
  open,
  setOpen,
  onSuccess,
  filters = [],
  search = "",
  totalCount = 0,
}) => {
  const batchUpdateMutation = useDatasetItemBatchUpdateMutation();
  const bulkEditItems = useBulkEditItems();
  const isAllItemsSelected = useIsAllItemsSelected();

  const handleUpdate = useCallback(
    async (tagsToAdd: string[], tagsToRemove: string[]) => {
      if (!isAllItemsSelected) {
        // Draft mode: update each item individually in local state
        rows.forEach((item) => {
          const currentTags = item?.tags || [];
          const finalTags = [
            ...currentTags.filter((t) => !tagsToRemove.includes(t)),
            ...tagsToAdd,
          ];
          bulkEditItems([item.id], { tags: finalTags });
        });

        if (onSuccess) {
          onSuccess();
        }
      } else {
        // API mode: call backend with update
        await batchUpdateMutation.mutateAsync({
          datasetId,
          itemIds: rows.map((row) => row.id),
          item: { tagsToAdd, tagsToRemove },
          isAllItemsSelected,
          filters,
          search,
          batchGroupId: isAllItemsSelected ? generateBatchGroupId() : undefined,
        });

        if (onSuccess) {
          onSuccess();
        }
      }
    },
    [
      datasetId,
      rows,
      isAllItemsSelected,
      filters,
      search,
      batchUpdateMutation,
      bulkEditItems,
      onSuccess,
    ],
  );

  return (
    <ManageTagsDialog
      entities={rows}
      open={open}
      setOpen={(value) => setOpen(Boolean(value))}
      onUpdate={handleUpdate}
      isAllItemsSelected={isAllItemsSelected}
      totalCount={totalCount}
    />
  );
};

export default AddTagDialog;
