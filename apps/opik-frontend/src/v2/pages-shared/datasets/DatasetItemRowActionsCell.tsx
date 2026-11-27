import React, { useCallback, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { Pencil, Trash } from "lucide-react";

import RowActions, { RowAction } from "@/shared/DataTable/RowActions";
import { DatasetItem } from "@/types/datasets";
import { useDeleteItem } from "@/store/TestSuiteDraftStore";
import RemoveDatasetItemsDialog from "./RemoveDatasetItemsDialog";
import { useDatasetItemDeletePreference } from "./hooks/useDatasetItemDeletePreference";

type CustomMeta = {
  setActiveRowId?: (id: string) => void;
};

export const DatasetItemRowActionsCell: React.FC<
  CellContext<DatasetItem, unknown>
> = (context) => {
  const datasetItem = context.row.original;
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [dontAskAgain] = useDatasetItemDeletePreference();
  const deleteItem = useDeleteItem();

  const { custom } = context.column.columnDef.meta ?? {};
  const { setActiveRowId } = (custom ?? {}) as CustomMeta;

  const performDelete = useCallback(() => {
    deleteItem(datasetItem.id);
  }, [datasetItem.id, deleteItem]);

  const handleDeleteClick = useCallback(() => {
    if (dontAskAgain) {
      performDelete();
    } else {
      setConfirmOpen(true);
    }
  }, [dontAskAgain, performDelete]);

  const handleEditClick = useCallback(() => {
    setActiveRowId?.(datasetItem.id);
  }, [datasetItem.id, setActiveRowId]);

  const rowActions: RowAction[] = [
    ...(setActiveRowId
      ? [
          {
            id: "edit",
            label: "Edit",
            icon: Pencil,
            onClick: handleEditClick,
          },
        ]
      : []),
    {
      id: "delete",
      label: "Delete",
      icon: Trash,
      onClick: handleDeleteClick,
      destructive: true,
    },
  ];

  return (
    <>
      <RemoveDatasetItemsDialog
        open={confirmOpen}
        setOpen={setConfirmOpen}
        onConfirm={performDelete}
      />
      <RowActions actions={rowActions} />
    </>
  );
};
