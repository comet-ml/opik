import React from "react";

import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { Separator } from "@/ui/separator";
import { cn } from "@/lib/utils";
import AssertionsField from "@/shared/AssertionField/AssertionsField";
import { Dataset, DATASET_TYPE } from "@/types/datasets";
import { MAX_RUNS_PER_ITEM } from "@/types/test-suites";
import {
  PASS_CRITERIA_TITLE,
  PASS_CRITERIA_DESCRIPTION,
} from "@/constants/test-suites";
import useDatasetForm from "@/v2/pages-shared/datasets/AddEditDatasetDialog/useDatasetForm";
import AddEditDatasetDialogWrapper from "@/v2/pages-shared/datasets/AddEditDatasetDialog/AddEditDatasetDialogWrapper";

type AddEditTestSuiteDialogProps = {
  dataset?: Dataset;
  open: boolean;
  setOpen: (open: boolean) => void;
  onDatasetCreated?: (dataset: Dataset) => void;
  hideUpload?: boolean;
  csvRequired?: boolean;
};

const AddEditTestSuiteDialog: React.FunctionComponent<
  AddEditTestSuiteDialogProps
> = ({ dataset, open, setOpen, onDatasetCreated, hideUpload, csvRequired }) => {
  const form = useDatasetForm({
    dataset,
    open,
    setOpen,
    onDatasetCreated,
    hideUpload,
    csvRequired,
    datasetType: DATASET_TYPE.TEST_SUITE,
  });

  const {
    isEdit,
    assertions,
    setAssertions,
    runsPerItem,
    runsInput,
    thresholdInput,
  } = form;

  return (
    <AddEditDatasetDialogWrapper
      open={open}
      setOpen={setOpen}
      form={form}
      hideUpload={hideUpload}
      idPrefix="testSuite"
    >
      {!isEdit && (
        <>
          <Separator className="mb-4" />
          <div className="mb-4">
            <h3 className="comet-body-s-accented">{PASS_CRITERIA_TITLE}</h3>
            <p className="comet-body-xs text-light-slate">
              {PASS_CRITERIA_DESCRIPTION}
            </p>
          </div>
          <div className="mb-4 flex gap-4">
            <div className="flex flex-1 flex-col gap-1">
              <Label htmlFor="runsPerItem" className="comet-body-s-accented">
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
              <Label htmlFor="passThreshold" className="comet-body-xs-accented">
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
              <Label className="comet-body-s-accented">Global assertions</Label>
              <p className="comet-body-xs text-light-slate">
                Define the global conditions all items in this test suite must
                pass.
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
                  setAssertions((prev) => prev.filter((_, i) => i !== index));
                }}
                onAdd={() => setAssertions((prev) => [...prev, ""])}
                placeholder="e.g. Response should be factually accurate and cite sources"
              />
            </div>
          </div>
        </>
      )}
    </AddEditDatasetDialogWrapper>
  );
};

export default AddEditTestSuiteDialog;
