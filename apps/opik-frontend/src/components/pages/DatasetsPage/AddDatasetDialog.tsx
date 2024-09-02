import React, { useCallback, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import useAppStore from "@/store/AppStore";
import useDatasetCreateMutation from "@/api/datasets/useDatasetCreateMutation";
import { Dataset } from "@/types/datasets";
import { Textarea } from "@/components/ui/textarea";

type AddDatasetDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  onDatasetCreated?: (dataset: Dataset) => void;
};

const AddDatasetDialog: React.FunctionComponent<AddDatasetDialogProps> = ({
  open,
  setOpen,
  onDatasetCreated,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const datasetCreateMutation = useDatasetCreateMutation();
  const [name, setName] = useState<string>("");
  const [description, setDescription] = useState<string>("");

  const isValid = Boolean(name.length);

  const createDataset = useCallback(() => {
    datasetCreateMutation.mutate(
      {
        dataset: {
          name,
          ...(description ? { description } : {}),
        },
        workspaceName,
      },
      { onSuccess: onDatasetCreated },
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [name, description, workspaceName, onDatasetCreated]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Create a new dataset</DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="datasetName">Name</Label>
          <Input
            id="datasetName"
            placeholder="Dataset name"
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
        </div>
        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="datasetDescription">Description</Label>
          <Textarea
            id="datasetDescription"
            placeholder="Dataset description"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            maxLength={255}
          />
        </div>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button type="submit" disabled={!isValid} onClick={createDataset}>
              Create dataset
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddDatasetDialog;
