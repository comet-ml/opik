import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";
import get from "lodash/get";
import slugify from "slugify";

import { Button } from "@/components/ui/button";
import { DatasetItem } from "@/types/datasets";
import useDatasetItemBatchDeleteMutation from "@/api/datasets/useDatasetItemBatchDeleteMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ExportToButton from "@/components/shared/ExportToButton/ExportToButton";

type DatasetItemsActionsPanelsProps = {
  datasetItems: DatasetItem[];
  columnsToExport: string[];
  datasetName: string;
  dynamicColumns: string[];
};

const DatasetItemsActionsPanel: React.FunctionComponent<
  DatasetItemsActionsPanelsProps
> = ({ datasetItems, columnsToExport, datasetName, dynamicColumns }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !datasetItems?.length;

  const { mutate } = useDatasetItemBatchDeleteMutation();

  const deleteDatasetItemsHandler = useCallback(() => {
    mutate({
      ids: datasetItems.map((i) => i.id),
    });
  }, [datasetItems, mutate]);

  const mapRowData = useCallback(() => {
    return datasetItems.map((row) => {
      return columnsToExport.reduce<Record<string, unknown>>((acc, column) => {
        if (dynamicColumns.includes(column)) {
          acc[column] = get(row, ["data", column], "");
        } else {
          acc[column] = get(row, column, "");
        }

        return acc;
      }, {});
    });
  }, [datasetItems, columnsToExport, dynamicColumns]);

  const generateFileName = useCallback(
    (extension = "csv") => {
      return `${slugify(datasetName, { lower: true })}-dataset-items.${extension}`;
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
        description="Are you sure you want to delete all selected dataset items?"
        confirmText="Delete dataset items"
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
      <ExportToButton
        disabled={disabled || columnsToExport.length === 0}
        getData={mapRowData}
        generateFileName={generateFileName}
      />
    </div>
  );
};

export default DatasetItemsActionsPanel;
