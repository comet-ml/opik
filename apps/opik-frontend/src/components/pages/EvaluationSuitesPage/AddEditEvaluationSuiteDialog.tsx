import React, { useCallback, useEffect, useState } from "react";
import { AxiosError, HttpStatusCode } from "axios";
import get from "lodash/get";

import useDatasetCreateMutation from "@/api/datasets/useDatasetCreateMutation";
import useDatasetUpdateMutation from "@/api/datasets/useDatasetUpdateMutation";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { useToast } from "@/components/ui/use-toast";
import { Dataset, DATASET_TYPE } from "@/types/datasets";

type AddEditEvaluationSuiteDialogProps = {
  dataset?: Dataset;
  open: boolean;
  setOpen: (open: boolean) => void;
  onDatasetCreated?: (dataset: Dataset) => void;
};

const AddEditEvaluationSuiteDialog: React.FunctionComponent<
  AddEditEvaluationSuiteDialogProps
> = ({ dataset, open, setOpen, onDatasetCreated }) => {
  const { toast } = useToast();

  const { mutate: createMutate } = useDatasetCreateMutation();
  const { mutate: updateMutate } = useDatasetUpdateMutation();

  const [name, setName] = useState<string>(dataset ? dataset.name : "");
  const [nameError, setNameError] = useState<string | undefined>(undefined);
  const [description, setDescription] = useState<string>(
    dataset ? dataset.description || "" : "",
  );

  useEffect(() => {
    setNameError(undefined);

    if (!open) {
      if (!dataset) {
        setName("");
        setDescription("");
      }
    } else if (dataset) {
      setName(dataset.name);
      setDescription(dataset.description || "");
    }
  }, [open, dataset]);

  const isEdit = Boolean(dataset);
  const isValid = Boolean(name.length);
  const title = isEdit
    ? "Edit evaluation suite"
    : "Create a new evaluation suite";
  const buttonText = isEdit
    ? "Update evaluation suite"
    : "Create evaluation suite";

  const handleMutationError = useCallback(
    (error: AxiosError, action: "create" | "update") => {
      const statusCode = get(error, ["response", "status"]);
      const errorMessage =
        get(error, ["response", "data", "message"]) ||
        get(error, ["response", "data", "errors", 0]) ||
        get(error, ["message"]);

      if (statusCode === HttpStatusCode.Conflict) {
        setNameError("This name already exists");
      } else {
        toast({
          title: `Error saving evaluation suite`,
          description: errorMessage || `Failed to ${action} evaluation suite`,
          variant: "destructive",
        });
        setOpen(false);
      }
    },
    [toast, setOpen],
  );

  const submitHandler = useCallback(() => {
    if (isEdit) {
      updateMutate(
        {
          dataset: {
            id: dataset!.id,
            name,
            ...(description && { description }),
          },
        },
        {
          onSuccess: () => {
            setOpen(false);
          },
          onError: (error: AxiosError) => handleMutationError(error, "update"),
        },
      );
    } else {
      createMutate(
        {
          dataset: {
            name,
            ...(description && { description }),
            type: DATASET_TYPE.EVALUATION_SUITE,
          },
        },
        {
          onSuccess: (newDataset: Dataset) => {
            setOpen(false);
            onDatasetCreated?.(newDataset);
          },
          onError: (error: AxiosError) => handleMutationError(error, "create"),
        },
      );
    }
  }, [
    isEdit,
    updateMutate,
    dataset,
    name,
    description,
    createMutate,
    onDatasetCreated,
    setOpen,
    handleMutationError,
  ]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="evaluationSuiteName">Name</Label>
            <Input
              id="evaluationSuiteName"
              placeholder="Evaluation suite name"
              value={name}
              className={
                nameError &&
                "!border-destructive focus-visible:!border-destructive"
              }
              onChange={(event) => {
                setName(event.target.value);
                setNameError(undefined);
              }}
              onKeyDown={(event) => {
                if (event.key === "Enter" && isValid) {
                  event.preventDefault();
                  submitHandler();
                }
              }}
            />
            <span
              className={`comet-body-xs min-h-4 ${
                nameError ? "text-destructive" : "invisible"
              }`}
            >
              {nameError || " "}
            </span>
          </div>
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="evaluationSuiteDescription">Description</Label>
            <Textarea
              id="evaluationSuiteDescription"
              placeholder="Evaluation suite description"
              className="min-h-20"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              maxLength={255}
            />
          </div>
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button type="submit" disabled={!isValid} onClick={submitHandler}>
            {buttonText}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditEvaluationSuiteDialog;
