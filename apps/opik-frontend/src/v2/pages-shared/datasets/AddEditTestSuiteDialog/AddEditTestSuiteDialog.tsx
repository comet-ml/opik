import { ExternalLink } from "lucide-react";

import { Button } from "@/ui/button";
import { Description } from "@/ui/description";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { Textarea } from "@/ui/textarea";
import { Separator } from "@/ui/separator";
import { cn, buildDocsUrl } from "@/lib/utils";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import UploadField from "@/shared/UploadField/UploadField";
import AssertionsField from "@/shared/AssertionField/AssertionsField";
import CsvUploadDialog from "@/v2/pages-shared/datasets/CsvUploadDialog/CsvUploadDialog";
import { Dataset } from "@/types/datasets";
import { MAX_RUNS_PER_ITEM } from "@/types/test-suites";
import useTestSuiteForm from "./useTestSuiteForm";

const ACCEPTED_TYPE = ".csv";

type AddEditTestSuiteDialogProps = {
  dataset?: Dataset;
  open: boolean;
  setOpen: (open: boolean) => void;
  onDatasetCreated?: (dataset: Dataset) => void;
  hideUpload?: boolean;
  csvRequired?: boolean;
};

const AddEditTestSuiteDialog = ({
  dataset,
  open,
  setOpen,
  onDatasetCreated,
  hideUpload,
  csvRequired = false,
}: AddEditTestSuiteDialogProps) => {
  const {
    name,
    setName,
    nameError,
    setNameError,
    description,
    setDescription,
    assertions,
    setAssertions,
    runsPerItem,
    runsInput,
    thresholdInput,
    csvFile,
    csvError,
    isEdit,
    isValid,
    isOverlayShown,
    confirmOpen,
    setConfirmOpen,
    isCsvUploadEnabled,
    fileSizeLimit,
    typeLabel,
    title,
    buttonText,
    submitHandler,
    handleFileSelect,
  } = useTestSuiteForm({
    dataset,
    open,
    setOpen,
    onDatasetCreated,
    hideUpload,
    csvRequired,
  });

  return (
    <>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-lg sm:max-w-[560px]">
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
          </DialogHeader>
          <DialogAutoScrollBody>
            <div className="flex flex-col gap-2 pb-4">
              <Label htmlFor="testSuiteName">Name</Label>
              <Input
                id="testSuiteName"
                placeholder="Name"
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
                    csvError ? setConfirmOpen(true) : submitHandler();
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
              <Label htmlFor="testSuiteDescription">Description</Label>
              <Textarea
                id="testSuiteDescription"
                placeholder="Description"
                className="min-h-20"
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                maxLength={255}
              />
            </div>
            {!isEdit && (
              <>
                <Separator className="mb-4" />
                <div className="mb-4">
                  <h3 className="comet-body-s-accented">Evaluation criteria</h3>
                  <p className="comet-body-xs text-light-slate">
                    Define the conditions required for the evaluation to pass
                  </p>
                </div>
                <div className="mb-4 flex gap-4">
                  <div className="flex flex-1 flex-col gap-1">
                    <Label
                      htmlFor="runsPerItem"
                      className="comet-body-s-accented"
                    >
                      Default runs per item
                    </Label>
                    <Input
                      id="runsPerItem"
                      dimension="sm"
                      className={cn({
                        "border-destructive": runsInput.isInvalid,
                      })}
                      type="number"
                      min={1}
                      max={MAX_RUNS_PER_ITEM}
                      value={runsInput.displayValue}
                      onChange={runsInput.onChange}
                      onFocus={runsInput.onFocus}
                      onBlur={runsInput.onBlur}
                      onKeyDown={runsInput.onKeyDown}
                    />
                  </div>
                  <div className="flex flex-1 flex-col gap-1">
                    <Label
                      htmlFor="passThreshold"
                      className="comet-body-xs-accented"
                    >
                      Default pass threshold
                    </Label>
                    <Input
                      id="passThreshold"
                      dimension="sm"
                      className={cn({
                        "border-destructive": thresholdInput.isInvalid,
                      })}
                      type="number"
                      min={1}
                      max={runsPerItem}
                      value={thresholdInput.displayValue}
                      onChange={thresholdInput.onChange}
                      onFocus={thresholdInput.onFocus}
                      onBlur={thresholdInput.onBlur}
                      onKeyDown={thresholdInput.onKeyDown}
                    />
                  </div>
                </div>
                <div className="flex flex-col gap-1 pb-4">
                  <div className="mb-1">
                    <Label className="comet-body-s-accented">
                      Global assertions
                    </Label>
                    <p className="comet-body-xs text-light-slate">
                      Define the global conditions all items in this evaluation
                      suite must pass.
                    </p>
                  </div>
                  <div className="pt-1.5">
                    <AssertionsField
                      editableAssertions={assertions}
                      onChangeEditable={(index, value) => {
                        setAssertions((prev) => {
                          const next = [...prev];
                          next[index] = value;
                          return next;
                        });
                      }}
                      onRemoveEditable={(index) => {
                        setAssertions((prev) =>
                          prev.filter((_, i) => i !== index),
                        );
                      }}
                      onAdd={() => setAssertions((prev) => [...prev, ""])}
                      placeholder="e.g. Response should be factually accurate and cite sources"
                    />
                  </div>
                </div>
              </>
            )}
            {!isEdit && !hideUpload && (
              <div className="flex flex-col gap-2 pb-4">
                <Label>Upload a CSV</Label>
                <Description className="tracking-normal">
                  {isCsvUploadEnabled ? (
                    <>
                      Your CSV file can be up to {fileSizeLimit}MB in size. The
                      file will be processed in the background.
                    </>
                  ) : (
                    <>
                      Your CSV file can contain up to 1,000 rows, for larger
                      test suites use the SDK instead.
                    </>
                  )}
                  <Button variant="link" size="sm" className="h-5 px-1" asChild>
                    <a
                      href={buildDocsUrl("/evaluation/manage_datasets")}
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      Learn more
                      <ExternalLink className="ml-0.5 size-3 shrink-0" />
                    </a>
                  </Button>
                </Description>
                <UploadField
                  disabled={isEdit}
                  description="Drop a CSV file to upload or"
                  accept={ACCEPTED_TYPE}
                  onFileSelect={handleFileSelect}
                  errorText={csvError}
                  successText={
                    csvFile && !csvError
                      ? "CSV file ready to upload"
                      : undefined
                  }
                />
              </div>
            )}
          </DialogAutoScrollBody>
          <DialogFooter>
            <DialogClose asChild>
              <Button variant="outline">
                {isOverlayShown ? "Close" : "Cancel"}
              </Button>
            </DialogClose>
            <Button
              type="submit"
              disabled={!isValid}
              onClick={csvError ? () => setConfirmOpen(true) : submitHandler}
            >
              {buttonText}
            </Button>
          </DialogFooter>
        </DialogContent>
        <ConfirmDialog
          open={confirmOpen}
          setOpen={setConfirmOpen}
          onCancel={submitHandler}
          title="File can't be uploaded"
          description={`This file cannot be uploaded because it does not pass validation. If you continue, the ${typeLabel} will be created without any items. You can add items manually later, or go back and upload a valid file.`}
          cancelText={`Create empty ${typeLabel}`}
          confirmText="Go back"
        />
      </Dialog>
      <CsvUploadDialog
        open={isOverlayShown}
        isCsvMode={isCsvUploadEnabled}
        onClose={() => setOpen(false)}
      />
    </>
  );
};

export default AddEditTestSuiteDialog;
