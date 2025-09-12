import React, { useCallback, useRef, useState } from "react";
import { Trash, Bot } from "lucide-react";

import { Button } from "@/components/ui/button";
import { DatasetItem } from "@/types/datasets";
import useDatasetItemBatchDeleteMutation from "@/api/datasets/useDatasetItemBatchDeleteMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import DatasetExpansionDialog from "./DatasetExpansionDialog";
import GeneratedSamplesDialog from "./GeneratedSamplesDialog";

type DatasetItemsActionsPanelsProps = {
  datasetItems: DatasetItem[];
  datasetId: string;
  datasetName: string;
};

const DatasetItemsActionsPanel: React.FunctionComponent<
  DatasetItemsActionsPanelsProps
> = ({ datasetItems, datasetId, datasetName }) => {
  const resetKeyRef = useRef(0);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState<boolean>(false);
  const [expansionDialogOpen, setExpansionDialogOpen] =
    useState<boolean>(false);
  const [generatedSamplesDialogOpen, setGeneratedSamplesDialogOpen] =
    useState<boolean>(false);
  const [generatedSamples, setGeneratedSamples] = useState<DatasetItem[]>([]);
  const disabled = !datasetItems?.length;

  const { mutate } = useDatasetItemBatchDeleteMutation();

  const deleteDatasetItemsHandler = useCallback(() => {
    mutate({
      ids: datasetItems.map((i) => i.id),
    });
  }, [datasetItems, mutate]);

  const handleSamplesGenerated = useCallback((samples: DatasetItem[]) => {
    setGeneratedSamples(samples);
    setGeneratedSamplesDialogOpen(true);
  }, []);

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
        datasetId={datasetId}
        open={expansionDialogOpen}
        setOpen={setExpansionDialogOpen}
        onSamplesGenerated={handleSamplesGenerated}
      />

      <GeneratedSamplesDialog
        datasetId={datasetId}
        datasetName={datasetName}
        samples={generatedSamples}
        open={generatedSamplesDialogOpen}
        setOpen={setGeneratedSamplesDialogOpen}
      />

      <TooltipWrapper content="Expand dataset with AI">
        <Button
          variant="default"
          size="icon-sm"
          onClick={() => setExpansionDialogOpen(true)}
          className="bg-blue-600 text-white hover:bg-blue-700"
        >
          <Bot />
        </Button>
      </TooltipWrapper>

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
