import React, { useCallback, useEffect, useState } from "react";
import { ChevronRight, ExternalLink } from "lucide-react";

import { Button } from "@/ui/button";
import { Description } from "@/ui/description";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { Separator } from "@/ui/separator";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import { Textarea } from "@/ui/textarea";
import { cn, buildDocsUrl, escapeJsString } from "@/lib/utils";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  CustomAccordionTrigger,
} from "@/ui/accordion";
import CopyButton from "@/shared/CopyButton/CopyButton";
import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import AssertionsField from "@/shared/AssertionField/AssertionsField";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import UploadField from "@/shared/UploadField/UploadField";
import CsvUploadDialog from "@/v2/pages-shared/datasets/CsvUploadDialog/CsvUploadDialog";
import useDatasetForm from "@/v2/pages-shared/datasets/AddEditDatasetDialog/useDatasetForm";
import useProjectById from "@/api/projects/useProjectById";
import { useActiveProjectId } from "@/store/AppStore";
import CreatedSuccess from "./CreatedSuccess";
import { Dataset, DATASET_TYPE, DatasetListType } from "@/types/datasets";
import { MAX_RUNS_PER_ITEM } from "@/types/test-suites";
import {
  PASS_CRITERIA_TITLE,
  PASS_CRITERIA_DESCRIPTION,
} from "@/constants/test-suites";

const ACCEPTED_TYPE = ".csv";

enum Step {
  NAME_DESCRIPTION,
  UPLOAD_AND_CONFIG,
  SUCCESS,
}

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

type CreateDatasetSidebarProps = {
  type: DatasetListType;
  open: boolean;
  setOpen: (open: boolean) => void;
  onDatasetCreated?: (dataset: Dataset) => void;
};

const CreateDatasetSidebar: React.FunctionComponent<
  CreateDatasetSidebarProps
> = ({ type, open, setOpen, onDatasetCreated }) => {
  const config = TYPE_CONFIG[type];
  const entityLabel =
    config.entityName[0].toLowerCase() + config.entityName.slice(1);

  const [step, setStep] = useState<Step>(Step.NAME_DESCRIPTION);
  const [createdName, setCreatedName] = useState("");
  const [navigateToEntity, setNavigateToEntity] = useState<(() => void) | null>(
    null,
  );
  const [sdkLanguage, setSdkLanguage] = useState<"python" | "typescript">(
    "python",
  );

  const activeProjectId = useActiveProjectId();
  const { data: activeProject } = useProjectById(
    { projectId: activeProjectId! },
    { enabled: !!activeProjectId },
  );
  const projectName = activeProject?.name ?? "";

  const handleNameConflict = useCallback(() => {
    setStep(Step.NAME_DESCRIPTION);
  }, []);

  const handleCreateSuccess = useCallback(
    (dataset: Dataset, navigate: () => void) => {
      setCreatedName(dataset.name);
      setNavigateToEntity(() => navigate);
      setStep(Step.SUCCESS);
    },
    [],
  );

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
    setIsOverlayShown,
    confirmOpen,
    setConfirmOpen,
    isCsvUploadEnabled,
    fileSizeLimit,
    typeLabel,
    submitHandler,
    handleFileSelect,
  } = useDatasetForm({
    open,
    setOpen,
    onDatasetCreated,
    skipEvaluationCriteria: config.skipEvaluationCriteria,
    datasetType: config.datasetType,
    onNameConflict: handleNameConflict,
    onCreateSuccess: handleCreateSuccess,
  });

  const escapedProjectName = projectName
    .replace(/\\/g, "\\\\")
    .replace(/"/g, '\\"');

  const escapedName = name.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
  const pythonSnippet =
    type === "test_suite"
      ? `import opik\n\nclient = opik.Opik()\nsuite = client.get_or_create_test_suite(name="${escapedName}", project_name="${escapedProjectName}")`
      : `import opik\n\nclient = opik.Opik()\ndataset = client.get_or_create_dataset(name="${escapedName}", project_name="${escapedProjectName}")`;

  const jsEscapedName = escapeJsString(name);
  const jsProjectName = escapeJsString(projectName);
  const typescriptSnippet =
    type === "test_suite"
      ? `import { Opik } from 'opik';\n\nconst client = new Opik();\nconst suite = await client.getOrCreateTestSuite({ name: "${jsEscapedName}", projectName: "${jsProjectName}" });`
      : `import { Opik } from 'opik';\n\nconst client = new Opik();\nconst dataset = await client.getOrCreateDataset("${jsEscapedName}", undefined, "${jsProjectName}");`;

  const activeSnippet =
    sdkLanguage === "python" ? pythonSnippet : typescriptSnippet;

  useEffect(() => {
    if (!open) {
      const timeout = setTimeout(() => {
        setStep(Step.NAME_DESCRIPTION);
        setCreatedName("");
        setNavigateToEntity(null);
        setSdkLanguage("python");
      }, 200);
      return () => clearTimeout(timeout);
    }
  }, [open]);

  const handleClose = useCallback(() => setOpen(false), [setOpen]);

  const handleGoToEntity = useCallback(() => {
    navigateToEntity?.();
  }, [navigateToEntity]);

  const handleOverlayClose = useCallback(() => {
    setIsOverlayShown(false);
  }, [setIsOverlayShown]);

  const handleCreateAnother = useCallback(() => {
    setStep(Step.NAME_DESCRIPTION);
    setCreatedName("");
    setNavigateToEntity(null);
    setName("");
    setDescription("");
    setAssertions([]);
    setSdkLanguage("python");
  }, [setName, setDescription, setAssertions]);

  const renderStepNameDescription = () => (
    <>
      <div className="flex flex-col gap-2 pb-4">
        <Label htmlFor={`${type}Name`}>Name</Label>
        <Input
          id={`${type}Name`}
          placeholder={`Name your ${entityLabel}...`}
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
              setStep(Step.UPLOAD_AND_CONFIG);
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
        <Label htmlFor={`${type}Description`}>Description</Label>
        <Textarea
          id={`${type}Description`}
          placeholder={`Describe your ${entityLabel}...`}
          className="min-h-28"
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          maxLength={255}
        />
      </div>
    </>
  );

  const dataKindPrefix = type === "test_suite" ? "test " : "";

  const renderStepUploadAndConfig = () => (
    <>
      <div className="mb-4">
        <h3 className="comet-body-s-accented">{`Add ${dataKindPrefix}data`}</h3>
        <p className="comet-body-xs text-light-slate">
          {`Choose how to provide your ${dataKindPrefix}data`}
        </p>
      </div>
      <div className="mb-4">
        <Label className="mb-2 block">Upload CSV</Label>
        <Description className="mb-2 tracking-normal">
          {isCsvUploadEnabled ? (
            <>
              Your CSV file can be up to {fileSizeLimit}MB in size. The file
              will be processed in the background.
            </>
          ) : (
            <>
              Your CSV file can contain up to 1,000 rows, for larger {typeLabel}
              s use the SDK instead.
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
          description="Drop a CSV file to upload or"
          accept={ACCEPTED_TYPE}
          onFileSelect={handleFileSelect}
          errorText={csvError}
          successText={
            csvFile && !csvError ? "CSV file ready to upload" : undefined
          }
        />
      </div>
      <div className="mb-4">
        <div className="mb-2 flex items-center justify-between">
          <Label>Use SDK</Label>
          <CopyButton
            text={activeSnippet}
            tooltipText="Copy code"
            message="Code copied to clipboard"
          />
        </div>
        <Tabs
          value={sdkLanguage}
          onValueChange={(v) => setSdkLanguage(v as "python" | "typescript")}
        >
          <TabsList variant="underline" className="mb-0 gap-4">
            <TabsTrigger variant="underline" size="sm" value="python">
              Python
            </TabsTrigger>
            <TabsTrigger variant="underline" size="sm" value="typescript">
              TypeScript
            </TabsTrigger>
          </TabsList>
          <TabsContent value="python" className="mt-0">
            <pre className="overflow-x-auto rounded-b-md border border-t-0 border-border bg-primary-foreground p-3 font-mono text-[13px] leading-relaxed">
              {pythonSnippet}
            </pre>
          </TabsContent>
          <TabsContent value="typescript" className="mt-0">
            <pre className="overflow-x-auto rounded-b-md border border-t-0 border-border bg-primary-foreground p-3 font-mono text-[13px] leading-relaxed">
              {typescriptSnippet}
            </pre>
          </TabsContent>
        </Tabs>
      </div>
      {type === "test_suite" && (
        <Accordion type="single" collapsible>
          <AccordionItem value="advanced" className="border-b-0">
            <CustomAccordionTrigger className="flex items-center gap-1.5 py-4 transition-all [&[data-state=open]>svg]:rotate-90">
              <ChevronRight className="size-4 shrink-0 transition-transform duration-200" />
              <span className="comet-body-s">Advanced settings</span>
            </CustomAccordionTrigger>
            <AccordionContent className="pl-6">
              <Separator className="mb-4" />
              <div className="mb-4">
                <h3 className="comet-body-s-accented">{PASS_CRITERIA_TITLE}</h3>
                <p className="comet-body-xs text-light-slate">
                  {PASS_CRITERIA_DESCRIPTION}
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
              <div className="flex flex-col gap-1 pb-4">
                <div className="mb-1">
                  <Label className="comet-body-s-accented">
                    Global assertions
                  </Label>
                  <p className="comet-body-xs text-light-slate">
                    Define the global conditions all items in this test suite
                    must pass.
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
            onClick={() => setStep(Step.UPLOAD_AND_CONFIG)}
          >
            Next
          </Button>
        </div>
      );
    }

    if (step === Step.UPLOAD_AND_CONFIG) {
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
            <Button
              onClick={csvError ? () => setConfirmOpen(true) : submitHandler}
            >
              Create
            </Button>
          </div>
        </div>
      );
    }

    return null;
  };

  return (
    <>
      <ResizableSidePanel
        panelId={`create-${type}-sidebar`}
        entity={typeLabel}
        open={open}
        onClose={handleClose}
        initialWidth={0.35}
        minWidth={450}
        closeButtonPosition="right"
        headerContent={
          <span className="comet-title-xs">{`Create ${entityLabel}`}</span>
        }
      >
        <div className="flex size-full flex-col">
          <div className="flex-1 overflow-y-auto p-6 pt-4">
            {step === Step.NAME_DESCRIPTION && renderStepNameDescription()}
            {step === Step.UPLOAD_AND_CONFIG && renderStepUploadAndConfig()}
            {step === Step.SUCCESS && (
              <CreatedSuccess
                entityName={config.entityName}
                name={createdName}
                onGoToEntity={handleGoToEntity}
                onCreateAnother={handleCreateAnother}
              />
            )}
          </div>
          {step !== Step.SUCCESS && (
            <div className="border-t px-6 py-4">{renderFooter()}</div>
          )}
        </div>
      </ResizableSidePanel>
      <ConfirmDialog
        open={confirmOpen}
        setOpen={setConfirmOpen}
        onCancel={submitHandler}
        title="File can't be uploaded"
        description={`This file cannot be uploaded because it does not pass validation. If you continue, the ${typeLabel} will be created without any items. You can add items manually later, or go back and upload a valid file.`}
        cancelText={`Create empty ${typeLabel}`}
        confirmText="Go back"
      />
      <CsvUploadDialog
        open={isOverlayShown}
        isCsvMode={isCsvUploadEnabled}
        onClose={handleOverlayClose}
      />
    </>
  );
};

export default CreateDatasetSidebar;
