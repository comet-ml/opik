import React from "react";
import { DatasetItem } from "@/types/datasets";
import { useDeleteItem } from "@/store/DatasetDraftStore";
import { RowActionsButtons } from "@/components/shared/DataTable/RowActionsButtons";

type DatasetItemRowActionsProps = {
  datasetItem: DatasetItem;
};

export const DatasetItemRowActions: React.FC<DatasetItemRowActionsProps> = ({
  datasetItem,
}) => {
  const deleteItem = useDeleteItem();

  const handleDelete = () => {
    deleteItem(datasetItem.id);
  };

  return (
    <RowActionsButtons actions={[{ type: "delete", onClick: handleDelete }]} />
  );
};
