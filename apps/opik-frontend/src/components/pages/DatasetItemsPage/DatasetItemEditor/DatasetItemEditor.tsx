import React from "react";
import { ColumnData } from "@/types/shared";
import { DatasetItem } from "@/types/datasets";
import { DatasetItemEditorProvider } from "./DatasetItemEditorContext";
import DatasetItemEditorLayout from "./DatasetItemEditorLayout";

interface DatasetItemEditorProps {
  datasetItemId: string;
  datasetId: string;
  columns: ColumnData<DatasetItem>[];
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
    <DatasetItemEditorProvider
      datasetItemId={datasetItemId}
      datasetId={datasetId}
      columns={columns}
      rows={rows}
      setActiveRowId={setActiveRowId}
    >
      <DatasetItemEditorLayout
        datasetItemId={datasetItemId}
        isOpen={isOpen}
        onClose={onClose}
      />
    </DatasetItemEditorProvider>
  );
};

export default DatasetItemEditor;
