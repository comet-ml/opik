import React from "react";
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
import { buildDocsUrl } from "@/lib/utils";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import UploadField from "@/shared/UploadField/UploadField";
import CsvUploadDialog from "@/v2/pages-shared/datasets/CsvUploadDialog/CsvUploadDialog";
import type useDatasetForm from "./useDatasetForm";

const ACCEPTED_TYPE = ".csv";

type AddEditDatasetDialogWrapperProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  form: ReturnType<typeof useDatasetForm>;
  hideUpload?: boolean;
  idPrefix: string;
  children?: React.ReactNode;
};

const AddEditDatasetDialogWrapper: React.FunctionComponent<
  AddEditDatasetDialogWrapperProps
> = ({ open, setOpen, form, hideUpload, idPrefix, children }) => {
  const {
    name,
    setName,
    nameError,
    setNameError,
    description,
    setDescription,
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
  } = form;

  return (
    <>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-lg sm:max-w-[560px]">
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
          </DialogHeader>
          <DialogAutoScrollBody>
            <div className="flex flex-col gap-2 pb-4">
              <Label htmlFor={`${idPrefix}Name`}>Name</Label>
              <Input
                id={`${idPrefix}Name`}
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
              <Label htmlFor={`${idPrefix}Description`}>Description</Label>
              <Textarea
                id={`${idPrefix}Description`}
                placeholder="Description"
                className="min-h-20"
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                maxLength={255}
              />
            </div>
            {children}
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
                      Your CSV file can contain up to 1,000 rows, for larger{" "}
                      {typeLabel}s use the SDK instead.
                    </>
                  )}
                  <Button variant="link" size="sm" className="h-5 px-1" asChild>
                    <a
                      href={buildDocsUrl("/evaluation/advanced/manage_datasets")}
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

export default AddEditDatasetDialogWrapper;
