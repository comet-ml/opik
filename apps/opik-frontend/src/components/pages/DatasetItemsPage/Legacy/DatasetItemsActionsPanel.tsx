import React, { useCallback, useRef, useState } from "react";
import { Trash, Sparkles, Tag } from "lucide-react";
import get from "lodash/get";
import slugify from "slugify";

import { Button } from "@/components/ui/button";
import { DatasetItem } from "@/types/datasets";
import useDatasetItemBatchDeleteMutation from "@/api/datasets/useDatasetItemBatchDeleteMutation";
import useDatasetItemBatchMutation from "@/api/datasets/useDatasetItemBatchMutation";
import useAppStore from "@/store/AppStore";
import { useToast } from "@/components/ui/use-toast";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import ExportToButton from "@/components/shared/ExportToButton/ExportToButton";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import DatasetExpansionDialog from "@/components/pages/DatasetItemsPage/DatasetExpansionDialog";
import GeneratedSamplesDialog from "@/components/pages/DatasetItemsPage/GeneratedSamplesDialog";
import AddTagDialog from "./AddTagDialog";
import { DATASET_ITEM_DATA_PREFIX } from "@/constants/datasets";
import { stripColumnPrefix } from "@/lib/utils";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { Filters } from "@/types/filters";

type DatasetItemsActionsPanelProps = {
  getDataForExport: () => Promise<DatasetItem[]>;
  selectedDatasetItems: DatasetItem[];
  datasetId: string;
  datasetName: string;
  columnsToExport: string[];
  dynamicColumns: string[];
  isAllItemsSelected?: boolean;
  filters?: Filters;
  search?: string;
  totalCount?: number;
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
  isAllItemsSelected = false,
  filters = [],
  search = "",
  totalCount = 0,
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
  const { mutate: addItemsMutate } = useDatasetItemBatchMutation();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { toast } = useToast();
  const isExportEnabled = useIsFeatureEnabled(FeatureToggleKeys.EXPORT_ENABLED);

  const deleteDatasetItemsHandler = useCallback(() => {
    mutate({
      datasetId,
      ids: selectedDatasetItems.map((i) => i.id),
      isAllItemsSelected,
      filters,
      search,
    });
  }, [
    datasetId,
    selectedDatasetItems,
    mutate,
    isAllItemsSelected,
    filters,
    search,
  ]);

  const handleSamplesGenerated = useCallback((samples: DatasetItem[]) => {
    setGeneratedSamples(samples);
    setGeneratedSamplesDialogOpen(true);
  }, []);

  const handleAddGeneratedItems = useCallback(
    (items: DatasetItem[]) => {
      addItemsMutate(
        {
          workspaceName,
          datasetId,
          datasetItems: items,
        },
        {
          onSuccess: () => {
            toast({
              title: "Samples added successfully",
              description: `${items.length} sample${
                items.length !== 1 ? "s" : ""
              } added to ${datasetName}`,
            });
          },
        },
      );
    },
    [addItemsMutate, workspaceName, datasetId, datasetName, toast],
  );

  const mapRowData = useCallback(async () => {
    const datasetItems = await getDataForExport();
    return datasetItems.map((item) => {
      return columnsToExport.reduce<Record<string, unknown>>((acc, column) => {
        // Check if this column is a dynamic dataset column
        if (dynamicColumns.includes(column)) {
          // Dynamic columns are stored in the item.data object
          const columnName = stripColumnPrefix(
            column,
            DATASET_ITEM_DATA_PREFIX,
          );
          acc[columnName] = get(item.data, columnName, "");
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
        samples={generatedSamples}
        open={generatedSamplesDialogOpen}
        setOpen={setGeneratedSamplesDialogOpen}
        onAddItems={handleAddGeneratedItems}
      />

      <AddTagDialog
        key={`tag-${resetKeyRef.current}`}
        datasetId={datasetId}
        rows={selectedDatasetItems}
        open={addTagDialogOpen}
        setOpen={setAddTagDialogOpen}
        onSuccess={() => {}}
        isAllItemsSelected={isAllItemsSelected}
        filters={filters}
        search={search}
        totalCount={totalCount}
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

      <TooltipWrapper content="Manage tags">
        <Button
          variant="outline"
          size="icon-sm"
          onClick={() => {
            setAddTagDialogOpen(true);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <Tag />
        </Button>
      </TooltipWrapper>

      <ExportToButton
        disabled={disabled || columnsToExport.length === 0 || !isExportEnabled}
        getData={mapRowData}
        generateFileName={generateFileName}
        tooltipContent={
          !isExportEnabled
            ? "Export functionality is disabled for this installation"
            : undefined
        }
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
