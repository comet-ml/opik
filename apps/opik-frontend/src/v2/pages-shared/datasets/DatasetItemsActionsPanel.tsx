import React, { useCallback, useRef, useState } from "react";
import { Trash, Tag } from "lucide-react";
import get from "lodash/get";
import slugify from "slugify";

import { Button } from "@/ui/button";
import { DatasetItem } from "@/types/datasets";
import useDatasetItemBatchDeleteMutation from "@/api/datasets/useDatasetItemBatchDeleteMutation";
import ExportToButton from "@/shared/ExportToButton/ExportToButton";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import AddTagDialog from "./AddTagDialog";
import { DATASET_ITEM_DATA_PREFIX } from "@/constants/datasets";
import { extractAssertions } from "@/lib/assertion-converters";
import { stripColumnPrefix, generateBatchGroupId } from "@/lib/utils";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { Filters } from "@/types/filters";
import {
  useBulkDeleteItems,
  useIsAllItemsSelected,
} from "@/store/TestSuiteDraftStore";
import { usePermissions } from "@/contexts/PermissionsContext";

type DatasetItemsActionsPanelProps = {
  getDataForExport: () => Promise<DatasetItem[]>;
  selectedDatasetItems: DatasetItem[];
  datasetId: string;
  datasetName: string;
  columnsToExport: string[];
  dynamicColumns: string[];
  filters?: Filters;
  search?: string;
  totalCount?: number;
  isDraftMode?: boolean;
  entityName?: string;
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
  filters = [],
  search = "",
  totalCount = 0,
  isDraftMode = false,
  entityName = "dataset",
}) => {
  const resetKeyRef = useRef(0);
  const [addTagDialogOpen, setAddTagDialogOpen] = useState<boolean>(false);
  const disabled = !selectedDatasetItems?.length;

  const { mutate } = useDatasetItemBatchDeleteMutation();
  const isExportEnabled = useIsFeatureEnabled(FeatureToggleKeys.EXPORT_ENABLED);
  const bulkDeleteItems = useBulkDeleteItems();
  const isAllItemsSelected = useIsAllItemsSelected();

  const {
    permissions: { canEditDatasets },
  } = usePermissions();

  const deleteDatasetItemsHandler = useCallback(() => {
    if (!isAllItemsSelected) {
      // Use draft store for specific IDs
      const ids = selectedDatasetItems.map((i) => i.id);
      bulkDeleteItems(ids);
    } else {
      // Use API for filter-based deletion
      mutate({
        datasetId,
        ids: selectedDatasetItems.map((i) => i.id),
        isAllItemsSelected,
        filters,
        search,
        batchGroupId: isAllItemsSelected ? generateBatchGroupId() : undefined,
      });
    }
  }, [
    datasetId,
    selectedDatasetItems,
    mutate,
    isAllItemsSelected,
    filters,
    search,
    bulkDeleteItems,
  ]);

  const mapRowData = useCallback(async () => {
    const normalizeExportValue = (value: unknown): unknown => {
      if (value === null || value === undefined) return "";
      if (Array.isArray(value) && value.length === 0) return "";
      if (typeof value === "object") return JSON.stringify(value);
      return value;
    };

    const datasetItems = await getDataForExport();
    return datasetItems.map((item) => {
      return columnsToExport.reduce<Record<string, unknown>>((acc, column) => {
        let key = column;
        let value: unknown;

        if (dynamicColumns.includes(column)) {
          const columnName = stripColumnPrefix(
            column,
            DATASET_ITEM_DATA_PREFIX,
          );
          key = columnName;
          value = get(item.data, columnName, "");
        } else if (column === "assertions") {
          value = extractAssertions(item.evaluators ?? []);
        } else {
          value = get(item, column, "");
        }

        acc[key] = normalizeExportValue(value);
        return acc;
      }, {});
    });
  }, [getDataForExport, columnsToExport, dynamicColumns]);

  const generateFileName = useCallback(
    (extension = "csv") => {
      return `${slugify(datasetName, {
        lower: true,
      })}-${slugify(entityName, { lower: true })}-items.${extension}`;
    },
    [datasetName, entityName],
  );

  return (
    <div className="flex items-center gap-2">
      <AddTagDialog
        key={`tag-${resetKeyRef.current}`}
        datasetId={datasetId}
        rows={selectedDatasetItems}
        open={addTagDialogOpen}
        setOpen={setAddTagDialogOpen}
        onSuccess={() => {}}
        filters={filters}
        search={search}
        totalCount={totalCount}
      />
      {canEditDatasets && (
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
      )}
      <ExportToButton
        disabled={
          disabled ||
          columnsToExport.length === 0 ||
          !isExportEnabled ||
          isDraftMode
        }
        getData={mapRowData}
        generateFileName={generateFileName}
        tooltipContent={
          !isExportEnabled
            ? "Export functionality is disabled for this installation"
            : undefined
        }
      />
      {canEditDatasets && (
        <TooltipWrapper content="Delete">
          <Button
            variant="outline"
            size="icon-sm"
            onClick={deleteDatasetItemsHandler}
            disabled={disabled}
          >
            <Trash />
          </Button>
        </TooltipWrapper>
      )}
    </div>
  );
};

export default DatasetItemsActionsPanel;
