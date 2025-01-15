import React, { useCallback, useState } from "react";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import useDatasetCreateMutation from "@/api/datasets/useDatasetCreateMutation";
import useDatasetUpdateMutation from "@/api/datasets/useDatasetUpdateMutation";
import { Dataset } from "@/types/datasets";

type AddEditDatasetDialogProps = {
  dataset?: Dataset;
  open: boolean;
  setOpen: (open: boolean) => void;
  onDatasetCreated?: (dataset: Dataset) => void;
};

const AddEditDatasetDialog: React.FunctionComponent<
  AddEditDatasetDialogProps
> = ({ dataset, open, setOpen, onDatasetCreated }) => {
  const { mutate: createMutate } = useDatasetCreateMutation();
  const { mutate: updateMutate } = useDatasetUpdateMutation();

  const [name, setName] = useState<string>(dataset ? dataset.name : "");
  const [description, setDescription] = useState<string>(
    dataset ? dataset.description || "" : "",
  );

  const isEdit = Boolean(dataset);
  const isValid = Boolean(name.length);
  const title = isEdit ? "Edit dataset" : "Create a new dataset";
  const buttonText = isEdit ? "Update dataset" : "Create dataset";

  const submitHandler = useCallback(() => {
    if (isEdit) {
      updateMutate({
        dataset: {
          id: dataset!.id,
          name,
          ...(description && { description }),
        },
      });
    } else {
      createMutate(
        {
          dataset: {
            name,
            ...(description && { description }),
          },
        },
        { onSuccess: onDatasetCreated },
      );
    }
  }, [
    createMutate,
    updateMutate,
    onDatasetCreated,
    dataset,
    name,
    description,
    isEdit,
  ]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="datasetName">Name</Label>
          <Input
            id="datasetName"
            placeholder="Dataset name"
            disabled={isEdit}
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
            <Button type="submit" disabled={!isValid} onClick={submitHandler}>
              {buttonText}
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditDatasetDialog;
