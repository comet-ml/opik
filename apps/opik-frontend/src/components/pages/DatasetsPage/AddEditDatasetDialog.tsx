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
import { Dataset, DATASET_ITEM_SOURCE } from "@/types/datasets";
import useDatasetItemBatchMutation from "@/api/datasets/useDatasetItemBatchMutation";
import useAppStore from "@/store/AppStore";
import UploadField from "@/components/shared/UploadField/UploadField";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import { Description } from "@/components/ui/description";
import { buildDocsUrl } from "@/lib/utils";
import { SquareArrowOutUpRight } from "lucide-react";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

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
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { mutate: createItemsMutate } = useDatasetItemBatchMutation();

  const [csvData, setCsvData] = useState<Record<string, unknown>[] | null>(
    null,
  );
  const [csvError, setCsvError] = useState<string | null>(null);

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
        {
          onSuccess: (newDataset: Dataset) => {
            if (csvData && csvData.length > 0) {
              // Prepare items with manual source and data fields
              const headers = Object.keys(csvData[0]);
              const [inputKey, outputKey] = headers;
              const items = csvData.map((row) => ({
                source: DATASET_ITEM_SOURCE.manual,
                data: {
                  [inputKey]: row[inputKey],
                  [outputKey]: row[outputKey],
                },
              }));
              createItemsMutate({
                datasetName: name,
                datasetItems: items,
                workspaceName,
              });
            }
            if (onDatasetCreated) {
              onDatasetCreated(newDataset);
            }
          },
        },
      );
    }
  }, [
    createMutate,
    updateMutate,
    createItemsMutate,
    workspaceName,
    onDatasetCreated,
    csvData,
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
        {!isEdit && (
          <ExplainerDescription
            className="mb-4"
            size="sm"
            {...EXPLAINERS_MAP[EXPLAINER_ID.why_do_i_need_multiple_datasets]}
          />
        )}
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
        {/* CSV upload section (optional) */}
        {!isEdit && (
          <div className="flex flex-col gap-2 pb-4">
            <Label>Upload a CSV (optional)</Label>
            <Description>
              Your CSV file should contain only two columns (input and output)
              and up to 1,000 rows. For larger datasets, use the SDK instead.{" "}
              <Button variant="link" size="3xs" asChild>
                <a
                  href={buildDocsUrl("/evaluation/manage_datasets")}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  Learn more
                  <SquareArrowOutUpRight className="ml-0.5 size-3 shrink-0" />
                </a>
              </Button>
              You can also skip this step and add dataset items manually later.
            </Description>
            <UploadField
              disabled={isEdit}
              accept=".csv"
              onFileSelect={(file) => {
                setCsvError(null);
                setCsvData(null);
                if (!file) return;
                (async () => {
                  try {
                    const text = await file.text();
                    const { csv2json } = await import("json-2-csv");
                    const parsed = await csv2json(text, { excelBOM: true });
                    if (!Array.isArray(parsed))
                      throw new Error("Invalid CSV format.");
                    if (parsed.length === 0)
                      throw new Error("CSV file is empty.");
                    if (parsed.length > 1000)
                      throw new Error("CSV file must have at most 1,000 rows.");
                    const headers = Object.keys(parsed[0] as object);
                    if (
                      headers.length !== 2 ||
                      !headers.includes("input") ||
                      !headers.includes("output")
                    ) {
                      throw new Error(
                        "CSV must contain exactly two columns named 'input' and 'output'.",
                      );
                    }
                    setCsvData(parsed as Record<string, unknown>[]);
                  } catch (error: unknown) {
                    if (error instanceof Error) {
                      setCsvError(error.message);
                    } else {
                      setCsvError("Failed to parse CSV file.");
                    }
                  }
                })();
              }}
              errorText={csvError ?? undefined}
              successText={
                csvData && !csvError ? "Valid CSV format" : undefined
              }
            />
          </div>
        )}
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button
              type="submit"
              disabled={!isValid || Boolean(csvError)}
              onClick={submitHandler}
            >
              {buttonText}
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditDatasetDialog;
