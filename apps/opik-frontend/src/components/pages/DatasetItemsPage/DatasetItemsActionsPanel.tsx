import React, { useCallback, useRef, useState } from "react";
import { Trash, Sparkles, Tag } from "lucide-react";
import get from "lodash/get";
import slugify from "slugify";

import { Button } from "@/components/ui/button";
import { DatasetItem } from "@/types/datasets";
import useDatasetItemBatchDeleteMutation from "@/api/datasets/useDatasetItemBatchDeleteMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import ExportToButton from "@/components/shared/ExportToButton/ExportToButton";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import DatasetExpansionDialog from "./DatasetExpansionDialog";
import GeneratedSamplesDialog from "./GeneratedSamplesDialog";
import AddTagDialog from "./AddTagDialog";

type DatasetItemsActionsPanelProps = {
  getDataForExport: () => Promise<DatasetItem[]>;
  selectedDatasetItems: DatasetItem[];
  datasetId: string;
  datasetName: string;
  columnsToExport: string[];
  dynamicColumns: string[];
};

const DatasetItemsActionsPanel: React.FunctionComponent<
  DatasetItemsActionsPanelProps
> = ({
  getDataForExport,
  selectedDatasetItems,
  datasetId,
  datasetName,
  columnsToExport,
  dynamicColumns,
}) => {
  const resetKeyRef = useRef(0);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState<boolean>(false);
  const [expansionDialogOpen, setExpansionDialogOpen] =
    useState<boolean>(false);
  const [generatedSamplesDialogOpen, setGeneratedSamplesDialogOpen] =
    useState<boolean>(false);
  const [generatedSamples, setGeneratedSamples] = useState<DatasetItem[]>([]);
  const [addTagDialogOpen, setAddTagDialogOpen] = useState<boolean>(false);
  const disabled = !selectedDatasetItems?.length;

  const { mutate } = useDatasetItemBatchDeleteMutation();

  const deleteDatasetItemsHandler = useCallback(() => {
    mutate({
      ids: selectedDatasetItems.map((i) => i.id),
    });
  }, [selectedDatasetItems, mutate]);

  const handleSamplesGenerated = useCallback((samples: DatasetItem[]) => {
    setGeneratedSamples(samples);
    setGeneratedSamplesDialogOpen(true);
  }, []);

  const mapRowData = useCallback(async () => {
    const datasetItems = await getDataForExport();
    return datasetItems.map((item) => {
      return columnsToExport.reduce<Record<string, unknown>>((acc, column) => {
        // Check if this column is a dynamic dataset column
        if (dynamicColumns.includes(column)) {
          // Dynamic columns are stored in the item.data object
          acc[column] = get(item.data, column, "");
        } else {
          // Handle direct properties like id, created_at, etc.
          acc[column] = get(item, column, "");
        }
        return acc;
      }, {});
    });
  }, [getDataForExport, columnsToExport, dynamicColumns]);

  const generateFileName = useCallback(
    (extension = "csv") => {
      return `${slugify(datasetName, {
        lower: true,
      })}-dataset-items.${extension}`;
    },
    [datasetName],
  );

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={deleteDialogOpen}
        setOpen={setDeleteDialogOpen}
        onConfirm={deleteDatasetItemsHandler}
        title="Delete dataset items"
        description="Deleting dataset items will also remove the related sample data from any linked experiments. This action can't be undone. Are you sure you want to continue?"
        confirmText="Delete dataset items"
        confirmButtonVariant="destructive"
      />

      <DatasetExpansionDialog
        key={`dataset-expansion-${resetKeyRef.current}`}
        datasetId={datasetId}
        open={expansionDialogOpen}
        setOpen={setExpansionDialogOpen}
        onSamplesGenerated={handleSamplesGenerated}
      />

      <GeneratedSamplesDialog
        key={`generate-samples-${resetKeyRef.current}`}
        datasetId={datasetId}
        datasetName={datasetName}
        samples={generatedSamples}
        open={generatedSamplesDialogOpen}
        setOpen={setGeneratedSamplesDialogOpen}
      />

      <AddTagDialog
        key={`tag-${resetKeyRef.current}`}
        datasetId={datasetId}
        rows={selectedDatasetItems}
        open={addTagDialogOpen}
        setOpen={setAddTagDialogOpen}
        onSuccess={() => {}}
      />

      <Button
        variant="secondary"
        size="sm"
        onClick={() => {
          setExpansionDialogOpen(true);
          resetKeyRef.current = resetKeyRef.current + 1;
        }}
      >
        <Sparkles className="mr-2 size-4" />
        Expand with AI
      </Button>

      <TooltipWrapper content="Add tags">
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            setAddTagDialogOpen(true);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <Tag className="mr-2 size-4" />
          Add tags
        </Button>
      </TooltipWrapper>

      <ExportToButton
        disabled={disabled || columnsToExport.length === 0}
        getData={mapRowData}
        generateFileName={generateFileName}
      />
      <TooltipWrapper content="Delete">
        <Button
          variant="outline"
          size="icon-sm"
          onClick={() => {
            setDeleteDialogOpen(true);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <Trash />
        </Button>
      </TooltipWrapper>
    </div>
  );
};

export default DatasetItemsActionsPanel;
