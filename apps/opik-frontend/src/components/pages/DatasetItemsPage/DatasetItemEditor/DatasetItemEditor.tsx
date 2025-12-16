import React from "react";
import { DatasetItem, DatasetItemColumn } from "@/types/datasets";
import { DatasetItemEditorAutosaveProvider } from "./DatasetItemEditorAutosaveContext";
import DatasetItemEditorAutosaveLayout from "./DatasetItemEditorAutosaveLayout";

interface DatasetItemEditorProps {
  datasetItemId: string;
  datasetId: string;
  columns: DatasetItemColumn[];
  onClose: () => void;
  isOpen: boolean;
  rows: DatasetItem[];
  setActiveRowId: (id: string) => void;
}

const DatasetItemEditor: React.FC<DatasetItemEditorProps> = ({
  datasetItemId,
  datasetId,
  columns,
  onClose,
  isOpen,
  rows,
  setActiveRowId,
}) => {
  return (
    <DatasetItemEditorAutosaveProvider
      datasetItemId={datasetItemId}
      datasetId={datasetId}
      columns={columns}
      rows={rows}
      setActiveRowId={setActiveRowId}
    >
      <DatasetItemEditorAutosaveLayout
        datasetItemId={datasetItemId}
        isOpen={isOpen}
        onClose={onClose}
      />
    </DatasetItemEditorAutosaveProvider>
  );
};

export default DatasetItemEditor;
