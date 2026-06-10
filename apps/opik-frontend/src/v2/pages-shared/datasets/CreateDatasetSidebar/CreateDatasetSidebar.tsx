import React, { useCallback, useEffect, useState } from "react";
import { ArrowUpRight, ChevronRight } from "lucide-react";

import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import { Textarea } from "@/ui/textarea";
import { escapeJsString } from "@/lib/utils";
import { buildDocsUrl } from "@/v2/lib/utils";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  CustomAccordionTrigger,
} from "@/ui/accordion";
import CodeHighlighter, {
  SUPPORTED_LANGUAGE,
} from "@/shared/CodeHighlighter/CodeHighlighter";

import { Spinner } from "@/ui/spinner";
import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import ResizableSidePanelTopBar from "@/shared/ResizableSidePanel/ResizableSidePanelTopBar";
import EvaluationCriteriaSection from "@/shared/EvaluationCriteriaSection/EvaluationCriteriaSection";
import DatasetCsvDropzone from "@/v2/pages-shared/datasets/DatasetCsvDropzone";
import useDatasetForm from "@/v2/pages-shared/datasets/AddEditDatasetDialog/useDatasetForm";
import useProjectById from "@/api/projects/useProjectById";
import { useActiveProjectId } from "@/store/AppStore";
import CreatedSuccess from "./CreatedSuccess";
import { Dataset, DATASET_TYPE, DatasetListType } from "@/types/datasets";

export type CreateDatasetMode = "upload" | "sdk";

const DOCS_URL = buildDocsUrl("/evaluation/advanced/manage_datasets");

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
  mode: CreateDatasetMode;
  open: boolean;
  setOpen: (open: boolean) => void;
  onDatasetCreated?: (dataset: Dataset) => void;
};

const CreateDatasetSidebar: React.FunctionComponent<
  CreateDatasetSidebarProps
> = ({ type, mode, open, setOpen, onDatasetCreated }) => {
  const config = TYPE_CONFIG[type];
  const entityLabel =
    config.entityName[0].toLowerCase() + config.entityName.slice(1);

  const [activeMode, setActiveMode] = useState<CreateDatasetMode>(mode);
  const [showSuccess, setShowSuccess] = useState(false);
  const [advancedOpen, setAdvancedOpen] = useState(false);
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

  const handleCreateSuccess = useCallback(
    (dataset: Dataset, navigate: () => void) => {
      setCreatedName(dataset.name);
      setNavigateToEntity(() => navigate);
      setShowSuccess(true);
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
    setRunsPerItem,
    passThreshold,
    setPassThreshold,
    uploadFile,
    uploadError,
    isSubmitting,
    typeLabel,
    submitHandler,
    handleFileSelect,
  } = useDatasetForm({
    open,
    setOpen,
    onDatasetCreated,
    skipEvaluationCriteria: config.skipEvaluationCriteria,
    appendDateToAutoName: true,
    datasetType: config.datasetType,
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

  // Sync the active mode with the option chosen in the create dropdown each
  // time the panel opens; the user can still switch modes from inside.
  useEffect(() => {
    if (open) {
      setActiveMode(mode);
    }
  }, [open, mode]);

  useEffect(() => {
    if (!open) {
      const timeout = setTimeout(() => {
        setShowSuccess(false);
        setAdvancedOpen(false);
        setCreatedName("");
        setNavigateToEntity(null);
        setSdkLanguage("python");
      }, 200);
      return () => clearTimeout(timeout);
    }
  }, [open]);

  const handleClose = useCallback(() => setOpen(false), [setOpen]);

  const hasValidUpload = uploadFile !== undefined && !uploadError;

  const isDirty =
    !showSuccess &&
    (name.length > 0 ||
      description.length > 0 ||
      uploadFile !== undefined ||
      assertions.length > 0);

  const canSubmit =
    activeMode === "upload"
      ? hasValidUpload && name.trim().length > 0
      : name.trim().length > 0;

  const handleGoToEntity = useCallback(() => {
    navigateToEntity?.();
  }, [navigateToEntity]);

  const handleCreateAnother = useCallback(() => {
    // "Create another" keeps the panel open, so the form hook's close-reset
    // never fires — reset every field-backing piece of state here.
    setShowSuccess(false);
    setAdvancedOpen(false);
    setCreatedName("");
    setNavigateToEntity(null);
    setName("");
    setDescription("");
    setAssertions([]);
    setRunsPerItem(1);
    setPassThreshold(1);
    handleFileSelect(undefined);
    setSdkLanguage("python");
  }, [
    setName,
    setDescription,
    setAssertions,
    setRunsPerItem,
    setPassThreshold,
    handleFileSelect,
  ]);

  const renderNameField = () => (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={`${type}Name`}>Name</Label>
      <Input
        id={`${type}Name`}
        dimension="sm"
        placeholder={`Name your ${entityLabel}...`}
        value={name}
        className={
          nameError && "!border-destructive focus-visible:!border-destructive"
        }
        onChange={(event) => {
          setName(event.target.value);
          setNameError(undefined);
        }}
      />
      {nameError && (
        <span className="comet-body-xs text-destructive">{nameError}</span>
      )}
    </div>
  );

  const renderDescriptionField = () => (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={`${type}Description`}>
        Description{" "}
        <span className="font-normal text-foreground">(optional)</span>
      </Label>
      <Textarea
        id={`${type}Description`}
        placeholder={`${config.entityName} description`}
        className="min-h-16 text-sm"
        value={description}
        onChange={(event) => setDescription(event.target.value)}
        maxLength={255}
      />
    </div>
  );

  const usesDefaultPolicy = runsPerItem === 1 && passThreshold === 1;

  const renderEvaluationCriteria = () => (
    <EvaluationCriteriaSection
      suiteAssertions={[]}
      editableAssertions={assertions}
      onChangeAssertion={(index, value) =>
        setAssertions((prev) => {
          const next = [...prev];
          next[index] = value;
          return next;
        })
      }
      onRemoveAssertion={(index) =>
        setAssertions((prev) => prev.filter((_, i) => i !== index))
      }
      onAddAssertion={() => setAssertions((prev) => [...prev, ""])}
      runsPerItem={runsPerItem}
      passThreshold={passThreshold}
      onRunsPerItemChange={setRunsPerItem}
      onPassThresholdChange={setPassThreshold}
      useGlobalPolicy={usesDefaultPolicy}
      onRevertToDefaults={() => {
        setRunsPerItem(1);
        setPassThreshold(1);
      }}
      defaultRunsPerItem={1}
      defaultPassThreshold={1}
    />
  );

  const renderUploadMode = () => (
    <>
      <Label className="mb-2 block">File</Label>
      <DatasetCsvDropzone
        uploadFile={uploadFile}
        uploadError={uploadError}
        onFileSelect={handleFileSelect}
        onUseSdk={() => {
          // Defensive: never carry a selected file into the code-only flow.
          handleFileSelect(undefined);
          setActiveMode("sdk");
        }}
      />
      {hasValidUpload && (
        <div className="mt-4 flex flex-col gap-3">
          {renderNameField()}
          {renderDescriptionField()}
          {type === "test_suite" && (
            <Accordion
              type="single"
              collapsible
              value={advancedOpen ? "advanced" : ""}
              onValueChange={(v) => setAdvancedOpen(v === "advanced")}
            >
              <AccordionItem value="advanced" className="border-b-0">
                <CustomAccordionTrigger className="flex items-center gap-1 transition-all [&[data-state=open]>svg]:rotate-90">
                  <span className="comet-body-xs">Advanced options</span>
                  <ChevronRight className="size-3.5 shrink-0 transition-transform duration-200" />
                </CustomAccordionTrigger>
                <AccordionContent className="pt-4">
                  {renderEvaluationCriteria()}
                </AccordionContent>
              </AccordionItem>
            </Accordion>
          )}
        </div>
      )}
    </>
  );

  const renderSdkMode = () => (
    <div className="flex flex-col gap-3">
      {renderNameField()}
      <Tabs
        value={sdkLanguage}
        onValueChange={(v) => setSdkLanguage(v as "python" | "typescript")}
      >
        <TabsList variant="segmented-primary">
          <TabsTrigger
            variant="segmented-primary"
            size="sm"
            value="python"
            className="h-5"
          >
            Python
          </TabsTrigger>
          <TabsTrigger
            variant="segmented-primary"
            size="sm"
            value="typescript"
            className="h-5"
          >
            TypeScript
          </TabsTrigger>
        </TabsList>
        <TabsContent value="python" className="mt-2">
          <div className="overflow-hidden rounded-md">
            <CodeHighlighter
              data={pythonSnippet}
              language={SUPPORTED_LANGUAGE.python}
            />
          </div>
        </TabsContent>
        <TabsContent value="typescript" className="mt-2">
          <div className="overflow-hidden rounded-md">
            <CodeHighlighter
              data={typescriptSnippet}
              language={SUPPORTED_LANGUAGE.python}
            />
          </div>
        </TabsContent>
      </Tabs>
      {renderDescriptionField()}
    </div>
  );

  const renderFooter = () => (
    <div className="flex items-center justify-end gap-2">
      <Button variant="outline" onClick={handleClose} disabled={isSubmitting}>
        Cancel
      </Button>
      <Button disabled={isSubmitting || !canSubmit} onClick={submitHandler}>
        {isSubmitting && <Spinner size="small" className="mr-2" />}
        {isSubmitting ? "Creating..." : `Create ${entityLabel}`}
      </Button>
    </div>
  );

  return (
    <ResizableSidePanel
      panelId={`create-${type}-sidebar`}
      entity={typeLabel}
      open={open}
      onClose={handleClose}
      initialWidth={0.35}
      minWidth={450}
      blockOverlayClose={isDirty}
      header={
        <ResizableSidePanelTopBar
          variant="form"
          title={
            <span className="comet-body-s-accented">{`Create ${entityLabel}`}</span>
          }
          onClose={handleClose}
        >
          {!showSuccess && (
            <Button variant="outline" size="2xs" asChild>
              <a href={DOCS_URL} target="_blank" rel="noopener noreferrer">
                Docs
                <ArrowUpRight className="ml-1 size-3 shrink-0" />
              </a>
            </Button>
          )}
        </ResizableSidePanelTopBar>
      }
    >
      <div className="flex size-full flex-col">
        <div className="flex-1 overflow-y-auto p-6 pt-4">
          {showSuccess ? (
            <CreatedSuccess
              entityName={config.entityName}
              name={createdName}
              onGoToEntity={handleGoToEntity}
              onCreateAnother={handleCreateAnother}
            />
          ) : activeMode === "upload" ? (
            renderUploadMode()
          ) : (
            renderSdkMode()
          )}
        </div>
        {!showSuccess && (
          <div className="border-t px-6 py-4">{renderFooter()}</div>
        )}
      </div>
    </ResizableSidePanel>
  );
};

export default CreateDatasetSidebar;
