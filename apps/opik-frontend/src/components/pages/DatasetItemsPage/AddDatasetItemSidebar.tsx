import React, { useCallback } from "react";
import { DatasetItemColumn } from "@/types/datasets";
import { AddDatasetItemProvider } from "./DatasetItemEditor/AddDatasetItemContext";
import AddDatasetItemSidebarLayout from "./DatasetItemEditor/AddDatasetItemSidebarLayout";

interface AddDatasetItemSidebarProps {
  datasetId: string;
  open: boolean;
  setOpen: (open: boolean) => void;
  columns: DatasetItemColumn[];
}

const AddDatasetItemSidebar: React.FC<AddDatasetItemSidebarProps> = ({
  datasetId,
  open,
  setOpen,
  columns,
}) => {
  const handleClose = useCallback(() => setOpen(false), [setOpen]);

  return (
    <AddDatasetItemProvider
      datasetId={datasetId}
      columns={columns}
      onClose={handleClose}
    >
      <AddDatasetItemSidebarLayout isOpen={open} onClose={handleClose} />
    </AddDatasetItemProvider>
  );
};

export default AddDatasetItemSidebar;
