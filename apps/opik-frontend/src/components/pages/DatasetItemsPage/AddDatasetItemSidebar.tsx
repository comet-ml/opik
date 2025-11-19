import React, { useCallback } from "react";
import { ColumnData } from "@/types/shared";
import { DatasetItem } from "@/types/datasets";
import { DatasetItemEditorProvider } from "./DatasetItemEditor/DatasetItemEditorContext";
import AddDatasetItemSidebarLayout from "./DatasetItemEditor/AddDatasetItemSidebarLayout";

interface AddDatasetItemSidebarProps {
  datasetId: string;
  open: boolean;
  setOpen: (open: boolean) => void;
  columns: ColumnData<DatasetItem>[];
}

const AddDatasetItemSidebar: React.FC<AddDatasetItemSidebarProps> = ({
  datasetId,
  open,
  setOpen,
  columns,
}) => {
  const handleClose = useCallback(() => setOpen(false), [setOpen]);

  return (
    <DatasetItemEditorProvider
      datasetId={datasetId}
      columns={columns}
      mode="create"
      onClose={handleClose}
    >
      <AddDatasetItemSidebarLayout isOpen={open} onClose={handleClose} />
    </DatasetItemEditorProvider>
  );
};

export default AddDatasetItemSidebar;
