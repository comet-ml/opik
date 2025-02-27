import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Dataset } from "@/types/datasets";
import useDatasetBatchDeleteMutation from "@/api/datasets/useDatasetBatchDeleteMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type DatasetsActionsPanelsProps = {
  datasets: Dataset[];
};

const DatasetsActionsPanel: React.FunctionComponent<
  DatasetsActionsPanelsProps
> = ({ datasets }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !datasets?.length;

  const { mutate } = useDatasetBatchDeleteMutation();

  const deleteDatasetsHandler = useCallback(() => {
    mutate({
      ids: datasets.map((d) => d.id),
    });
  }, [datasets, mutate]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteDatasetsHandler}
        title="Delete datasets"
        description="Are you sure you want to delete all selected datasets?"
        confirmText="Delete datasets"
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

export default DatasetsActionsPanel;
