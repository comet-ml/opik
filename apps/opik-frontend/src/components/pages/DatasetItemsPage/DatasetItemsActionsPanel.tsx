import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";

import { Button } from "@/components/ui/button";
import { DatasetItem } from "@/types/datasets";
import useDatasetItemBatchDeleteMutation from "@/api/datasets/useDatasetItemBatchDeleteMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type DatasetItemsActionsPanelsProps = {
  datasetItems: DatasetItem[];
};

const DatasetItemsActionsPanel: React.FunctionComponent<
  DatasetItemsActionsPanelsProps
> = ({ datasetItems }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !datasetItems?.length;

  const { mutate } = useDatasetItemBatchDeleteMutation();

  const deleteDatasetItemsHandler = useCallback(() => {
    mutate({
      ids: datasetItems.map((i) => i.id),
    });
  }, [datasetItems, mutate]);

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
          size="icon"
          onClick={() => {
            setOpen(true);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <Trash className="size-4" />
        </Button>
      </TooltipWrapper>
    </div>
  );
};

export default DatasetItemsActionsPanel;
