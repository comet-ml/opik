import React, { useCallback } from "react";

import ManageTagsDialog from "@/components/shared/ManageTagsDialog/ManageTagsDialog";
import useDatasetItemBatchUpdateMutation from "@/api/datasets/useDatasetItemBatchUpdateMutation";
import { generateBatchGroupId } from "@/lib/utils";
import { DatasetItem } from "@/types/datasets";
import { Filters } from "@/types/filters";

type AddTagDialogProps = {
  datasetId: string;
  rows: Array<DatasetItem>;
  open: boolean;
  setOpen: (open: boolean) => void;
  onSuccess?: () => void;
  isAllItemsSelected?: boolean;
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
  isAllItemsSelected = false,
  filters = [],
  search = "",
  totalCount = 0,
}) => {
  const batchUpdateMutation = useDatasetItemBatchUpdateMutation();

  const handleUpdate = useCallback(
    async (tagsToAdd: string[], tagsToRemove: string[]) => {
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
    },
    [
      datasetId,
      rows,
      isAllItemsSelected,
      filters,
      search,
      batchUpdateMutation,
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
