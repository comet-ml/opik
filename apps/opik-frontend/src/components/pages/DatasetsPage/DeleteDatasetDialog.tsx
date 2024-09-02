import React, { useCallback, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Dataset } from "@/types/datasets";
import useDatasetDeleteMutation from "@/api/datasets/useDatasetDeleteMutation";
import useAppStore from "@/store/AppStore";

type DeleteDatasetDialogProps = {
  dataset: Dataset;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const DeleteDatasetDialog: React.FunctionComponent<
  DeleteDatasetDialogProps
> = ({ dataset, open, setOpen }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const [validation, setValidation] = useState<string>("");
  const datasetDeleteMutation = useDatasetDeleteMutation();

  const isValid = validation === dataset.name;

  const deleteDataset = useCallback(() => {
    datasetDeleteMutation.mutate({
      datasetId: dataset.id,
      workspaceName,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dataset.id, workspaceName]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{`Delete ${dataset.name}`}</DialogTitle>
          <DialogDescription>
            Once a dataset is deleted, all data associated with it is deleted
            and cannot be recovered.
          </DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-4">
          <Label htmlFor="datasetName">
            {`To validation, type "${dataset.name}"`}
          </Label>
          <Input
            id="datasetName"
            placeholder=""
            value={validation}
            onChange={(event) => setValidation(event.target.value)}
          />
        </div>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button type="submit" disabled={!isValid} onClick={deleteDataset}>
              Delete
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default DeleteDatasetDialog;
