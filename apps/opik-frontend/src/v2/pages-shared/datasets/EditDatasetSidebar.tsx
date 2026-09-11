import React, { useCallback } from "react";

import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { Textarea } from "@/ui/textarea";
import { Spinner } from "@/ui/spinner";
import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import ResizableSidePanelTopBar from "@/shared/ResizableSidePanel/ResizableSidePanelTopBar";
import useDatasetForm from "@/v2/pages-shared/datasets/AddEditDatasetDialog/useDatasetForm";
import { Dataset, DATASET_TYPE, DatasetListType } from "@/types/datasets";

const TYPE_CONFIG = {
  dataset: {
    entityName: "Dataset",
    datasetType: DATASET_TYPE.DATASET,
    skipEvaluationCriteria: true,
  },
  test_suite: {
    entityName: "Test suite",
    datasetType: DATASET_TYPE.TEST_SUITE,
    skipEvaluationCriteria: false,
  },
} as const;

type EditDatasetSidebarProps = {
  type: DatasetListType;
  dataset?: Dataset;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const EditDatasetSidebar: React.FunctionComponent<EditDatasetSidebarProps> = ({
  type,
  dataset,
  open,
  setOpen,
}) => {
  const config = TYPE_CONFIG[type];
  const entityLabel =
    config.entityName[0].toLowerCase() + config.entityName.slice(1);

  // Editing only changes name + description — upload and evaluation criteria
  // are create-only (gated by `!isEdit` in the original dialog).
  const {
    name,
    setName,
    nameError,
    setNameError,
    description,
    setDescription,
    isValid,
    isSubmitting,
    typeLabel,
    submitHandler,
  } = useDatasetForm({
    dataset,
    open,
    setOpen,
    hideUpload: true,
    skipEvaluationCriteria: config.skipEvaluationCriteria,
    datasetType: config.datasetType,
  });

  const handleClose = useCallback(() => setOpen(false), [setOpen]);

  return (
    <ResizableSidePanel
      panelId={`edit-${type}-sidebar`}
      entity={typeLabel}
      open={open}
      onClose={handleClose}
      initialWidth={0.5}
      minWidth={450}
      blockOverlayClose
      header={
        <ResizableSidePanelTopBar
          variant="form"
          title={
            <span className="comet-body-s-accented">{`Edit ${entityLabel}`}</span>
          }
          onClose={handleClose}
        />
      }
    >
      <div className="flex size-full flex-col">
        <div className="flex-1 overflow-y-auto pb-6 pl-9 pr-4 pt-4">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${type}EditName`}>Name</Label>
            <Input
              id={`${type}EditName`}
              dimension="sm"
              placeholder={`Name your ${entityLabel}...`}
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
                if (event.key === "Enter" && isValid && !isSubmitting) {
                  event.preventDefault();
                  submitHandler();
                }
              }}
            />
            {nameError && (
              <span className="comet-body-xs text-destructive">
                {nameError}
              </span>
            )}
          </div>
          <div className="mt-3 flex flex-col gap-1.5">
            <Label htmlFor={`${type}EditDescription`}>
              Description{" "}
              <span className="font-normal text-foreground">(optional)</span>
            </Label>
            <Textarea
              id={`${type}EditDescription`}
              placeholder={`${config.entityName} description`}
              className="min-h-16 text-sm"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              maxLength={255}
            />
          </div>
        </div>
        <div className="border-t py-4 pl-9 pr-4">
          <div className="flex items-center justify-end gap-2">
            <Button
              variant="outline"
              onClick={handleClose}
              disabled={isSubmitting}
            >
              Cancel
            </Button>
            <Button disabled={!isValid || isSubmitting} onClick={submitHandler}>
              {isSubmitting && <Spinner size="small" className="mr-2" />}
              {isSubmitting ? "Saving..." : "Save changes"}
            </Button>
          </div>
        </div>
      </div>
    </ResizableSidePanel>
  );
};

export default EditDatasetSidebar;
