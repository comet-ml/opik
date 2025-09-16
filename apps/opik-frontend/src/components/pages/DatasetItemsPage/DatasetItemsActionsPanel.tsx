import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";
import get from "lodash/get";
import slugify from "slugify";

import { Button } from "@/components/ui/button";
import { DatasetItem } from "@/types/datasets";
import useDatasetItemBatchDeleteMutation from "@/api/datasets/useDatasetItemBatchDeleteMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import ExportToButton from "@/components/shared/ExportToButton/ExportToButton";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type DatasetItemsActionsPanelProps = {
  datasetItems: DatasetItem[];
  columnsToExport: string[];
  datasetName: string;
  dynamicColumns: string[];
};

const DatasetItemsActionsPanel: React.FunctionComponent<
  DatasetItemsActionsPanelProps
> = ({ datasetItems, columnsToExport, datasetName, dynamicColumns }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);

  const { mutate } = useDatasetItemBatchDeleteMutation();
  const disabled = !datasetItems?.length;

  const deleteDatasetItemsHandler = useCallback(() => {
    mutate({
      ids: datasetItems.map((i) => i.id),
    });
  }, [datasetItems, mutate]);

  const mapRowData = useCallback(() => {
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
  }, [datasetItems, columnsToExport, dynamicColumns]);

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
        open={open}
        setOpen={setOpen}
        onConfirm={deleteDatasetItemsHandler}
        title="Delete dataset items"
        description="Deleting dataset items will also remove the related sample data from any linked experiments. This action can't be undone. Are you sure you want to continue?"
        confirmText="Delete dataset items"
        confirmButtonVariant="destructive"
      />
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
            setOpen(true);
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
