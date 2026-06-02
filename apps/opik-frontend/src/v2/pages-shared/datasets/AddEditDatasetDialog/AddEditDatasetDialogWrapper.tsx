import React from "react";

import { Button } from "@/ui/button";
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
import { buildDocsUrl } from "@/v2/lib/utils";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import DatasetUploadDescription from "@/shared/DatasetUploadDescription/DatasetUploadDescription";
import DatasetUploadField from "@/shared/DatasetUploadField/DatasetUploadField";
import type useDatasetForm from "./useDatasetForm";

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
    uploadFile,
    uploadError,
    uploadFormat,
    isEdit,
    isValid,
    confirmOpen,
    setConfirmOpen,
    fileSizeLimit,
    typeLabel,
    title,
    buttonText,
    submitHandler,
    handleFileSelect,
  } = form;

  return (
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
                  uploadError ? setConfirmOpen(true) : submitHandler();
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
              <Label>Upload a CSV or JSON file</Label>
              <DatasetUploadDescription
                fileSizeLimit={fileSizeLimit}
                docsUrl={buildDocsUrl("/evaluation/advanced/manage_datasets")}
              />
              <DatasetUploadField
                uploadFile={uploadFile}
                uploadFormat={uploadFormat}
                uploadError={uploadError}
                onFileSelect={handleFileSelect}
                disabled={isEdit}
              />
            </div>
          )}
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button
            type="submit"
            disabled={!isValid}
            onClick={uploadError ? () => setConfirmOpen(true) : submitHandler}
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
  );
};

export default AddEditDatasetDialogWrapper;
