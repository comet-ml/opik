import { useCallback, useEffect, useMemo, useState } from "react";
import { ChevronRight } from "lucide-react";

import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { Textarea } from "@/ui/textarea";
import { cn, buildDocsUrl } from "@/lib/utils";
import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import CopyButton from "@/shared/CopyButton/CopyButton";
import UploadField from "@/shared/UploadField/UploadField";
import AssertionsField from "@/shared/AssertionField/AssertionsField";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import CsvUploadDialog from "@/v2/pages-shared/datasets/CsvUploadDialog/CsvUploadDialog";
import useEvaluationSuiteForm from "@/v2/pages-shared/datasets/AddEditEvaluationSuiteDialog/useEvaluationSuiteForm";
import { Dataset } from "@/types/datasets";
import { MAX_RUNS_PER_ITEM } from "@/types/evaluation-suites";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  CustomAccordionTrigger,
} from "@/ui/accordion";

const ACCEPTED_TYPE = ".csv";

type EvaluationType = "regression" | "metric";

enum Step {
  NAME_DESCRIPTION,
  EVALUATION_TYPE,
  TEST_DATA,
}

type AddEvaluationSuiteSidebarProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  onDatasetCreated?: (dataset: Dataset) => void;
};

const AddEvaluationSuiteSidebar = ({
  open,
  setOpen,
  onDatasetCreated,
}: AddEvaluationSuiteSidebarProps) => {
  const [step, setStep] = useState<Step>(Step.NAME_DESCRIPTION);
  const [evaluationType, setEvaluationType] =
    useState<EvaluationType>("regression");

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
    isOverlayShown,
    confirmOpen,
    setConfirmOpen,
    isCsvUploadEnabled,
    fileSizeLimit,
    submitHandler,
    handleFileSelect,
  } = useEvaluationSuiteForm({
    open,
    setOpen,
    onDatasetCreated,
    skipEvaluationCriteria: evaluationType !== "regression",
    onNameConflict: () => setStep(Step.NAME_DESCRIPTION),
  });

  useEffect(() => {
    if (!open) {
      setStep(Step.NAME_DESCRIPTION);
      setEvaluationType("regression");
    }
  }, [open]);

  const sdkSnippet = useMemo(() => {
    const escapedName = (name || "my-suite").replace(/"/g, '\\"');
    return `import { EvalSuite } from '@opik/sdk';

const suite = new EvalSuite({
  name: "${escapedName}",
  type: "${evaluationType}"
});

await suite.run(testCases);`;
  }, [name, evaluationType]);

  const handleClose = useCallback(() => setOpen(false), [setOpen]);

  const renderStepNameDescription = () => (
    <>
      <div className="flex flex-col gap-2 pb-4">
        <Label htmlFor="evaluationSuiteName">Name</Label>
        <Input
          id="evaluationSuiteName"
          placeholder="Marketing Copy Variations"
          value={name}
          className={
            nameError && "!border-destructive focus-visible:!border-destructive"
          }
          onChange={(event) => {
            setName(event.target.value);
            setNameError(undefined);
          }}
          onKeyDown={(event) => {
            if (event.key === "Enter" && name.length > 0) {
              event.preventDefault();
              setStep(Step.EVALUATION_TYPE);
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
          placeholder="Describe your evaluation suite..."
          className="min-h-28"
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          maxLength={255}
        />
      </div>
    </>
  );

  const renderStepEvaluationType = () => (
    <>
      <div className="mb-4">
        <h3 className="comet-body-s-accented">Select evaluation type</h3>
        <p className="comet-body-xs text-light-slate">
          Choose how you want to evaluate your results
        </p>
      </div>
      <div className="flex flex-col gap-3">
        <button
          type="button"
          className={cn(
            "cursor-pointer rounded-lg border p-4 text-left transition-colors hover:border-primary",
            evaluationType === "regression"
              ? "border-primary bg-primary/5"
              : "border-border",
          )}
          onClick={() => setEvaluationType("regression")}
        >
          <div className="comet-body-s-accented">Regression test</div>
          <p className="comet-body-xs text-light-slate">
            Test against assertions to catch regressions
          </p>
        </button>
        <button
          type="button"
          className={cn(
            "cursor-pointer rounded-lg border p-4 text-left transition-colors hover:border-primary",
            evaluationType === "metric"
              ? "border-primary bg-primary/5"
              : "border-border",
          )}
          onClick={() => setEvaluationType("metric")}
        >
          <div className="comet-body-s-accented">Evaluate against Metric</div>
          <p className="comet-body-xs text-light-slate">
            Qualitative evaluation using metrics
          </p>
        </button>
      </div>
    </>
  );

  const renderStepTestData = () => (
    <>
      <div className="mb-4">
        <h3 className="comet-body-s-accented">Add test data</h3>
        <p className="comet-body-xs text-light-slate">
          Choose how to provide your test data
        </p>
      </div>
      <div className="mb-4">
        <Label className="mb-2 block">Upload CSV</Label>
        <UploadField
          description="Drop CSV or Browse files"
          accept={ACCEPTED_TYPE}
          onFileSelect={handleFileSelect}
          errorText={csvError}
          successText={
            csvFile && !csvError ? "CSV file ready to upload" : undefined
          }
        />
        <p className="comet-body-xs mt-1.5 text-light-slate">
          {isCsvUploadEnabled
            ? `Up to ${fileSizeLimit}MB. File will be processed in the background.`
            : "Up to 1,000 rows. Use SDK for larger files."}{" "}
          <a
            href={buildDocsUrl("/evaluation/manage_datasets")}
            target="_blank"
            rel="noopener noreferrer"
            className="text-primary"
          >
            Learn more
          </a>
        </p>
      </div>
      <div className="mb-4">
        <div className="mb-2 flex items-center justify-between">
          <Label>Use SDK</Label>
          <CopyButton
            text={sdkSnippet}
            message="Successfully copied code"
            tooltipText="Copy"
          />
        </div>
        <pre className="overflow-x-auto rounded-md border border-border bg-primary-foreground p-3 font-mono text-[13px] leading-relaxed">
          {sdkSnippet}
        </pre>
      </div>
      {evaluationType === "regression" && (
        <Accordion type="single" collapsible>
          <AccordionItem value="advanced" className="border-b-0">
            <CustomAccordionTrigger className="flex items-center gap-1.5 py-4 transition-all [&[data-state=open]>svg]:rotate-90">
              <ChevronRight className="size-4 shrink-0 transition-transform duration-200" />
              <span className="comet-body-s">Advanced</span>
            </CustomAccordionTrigger>
            <AccordionContent className="pl-6">
              <div className="mb-4">
                <h4 className="comet-body-s-accented">Evaluation criteria</h4>
                <p className="comet-body-xs text-light-slate">
                  Define the conditions required for the evaluation to pass
                </p>
              </div>
              <div className="mb-4 flex gap-4">
                <div className="flex flex-1 flex-col gap-1">
                  <Label
                    htmlFor="runsPerItem"
                    className="comet-body-xs-accented"
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
              <div className="flex flex-col gap-1">
                <div className="mb-1">
                  <Label>Global assertions</Label>
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
            </AccordionContent>
          </AccordionItem>
        </Accordion>
      )}
    </>
  );

  const renderFooter = () => {
    if (step === Step.NAME_DESCRIPTION) {
      return (
        <div className="flex items-center justify-end gap-2">
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button
            disabled={name.length === 0}
            onClick={() => setStep(Step.EVALUATION_TYPE)}
          >
            Next
          </Button>
        </div>
      );
    }

    if (step === Step.EVALUATION_TYPE) {
      return (
        <div className="flex items-center justify-between">
          <Button
            variant="outline"
            onClick={() => setStep(Step.NAME_DESCRIPTION)}
          >
            Back
          </Button>
          <div className="flex items-center gap-2">
            <Button variant="outline" onClick={handleClose}>
              Cancel
            </Button>
            <Button onClick={() => setStep(Step.TEST_DATA)}>Next</Button>
          </div>
        </div>
      );
    }

    return (
      <div className="flex items-center justify-between">
        <Button variant="outline" onClick={() => setStep(Step.EVALUATION_TYPE)}>
          Back
        </Button>
        <div className="flex items-center gap-2">
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button
            onClick={csvError ? () => setConfirmOpen(true) : submitHandler}
          >
            Create
          </Button>
        </div>
      </div>
    );
  };

  return (
    <>
      <ResizableSidePanel
        panelId="add-evaluation-suite-sidebar"
        entity="evaluation suite"
        open={open}
        onClose={handleClose}
        initialWidth={0.45}
        closeButtonPosition="right"
        headerContent={
          <span className="comet-title-xxs">Create evaluation suite</span>
        }
      >
        <div className="flex size-full flex-col">
          <div className="flex-1 overflow-y-auto p-6 pt-4">
            {step === Step.NAME_DESCRIPTION && renderStepNameDescription()}
            {step === Step.EVALUATION_TYPE && renderStepEvaluationType()}
            {step === Step.TEST_DATA && renderStepTestData()}
          </div>
          <div className="border-t px-6 py-4">{renderFooter()}</div>
        </div>
      </ResizableSidePanel>
      <ConfirmDialog
        open={confirmOpen}
        setOpen={setConfirmOpen}
        onCancel={submitHandler}
        title="File can't be uploaded"
        description="This file cannot be uploaded because it does not pass validation. If you continue, the evaluation suite will be created without any items. You can add items manually later, or go back and upload a valid file."
        cancelText="Create empty evaluation suite"
        confirmText="Go back"
      />
      <CsvUploadDialog
        open={isOverlayShown}
        isCsvMode={isCsvUploadEnabled}
        onClose={handleClose}
      />
    </>
  );
};

export default AddEvaluationSuiteSidebar;
