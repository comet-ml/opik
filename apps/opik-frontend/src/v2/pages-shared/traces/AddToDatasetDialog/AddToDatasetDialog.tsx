import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import isUndefined from "lodash/isUndefined";
import { Database, ListChecks, MessageCircleWarning, Plus } from "lucide-react";
import { keepPreviousData } from "@tanstack/react-query";

import { Span, Trace } from "@/types/traces";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/ui/accordion";
import useProjectDatasetsList from "@/api/datasets/useProjectDatasetsList";
import useAddTracesToDatasetMutation from "@/api/datasets/useAddTracesToDatasetMutation";
import useAddSpansToDatasetMutation from "@/api/datasets/useAddSpansToDatasetMutation";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import { Dataset, DATASET_TYPE } from "@/types/datasets";
import { COLUMN_TYPE, DropdownOption } from "@/types/shared";
import { Filter } from "@/types/filters";
import { DEFAULT_EXECUTION_POLICY } from "@/types/test-suites";
import { Alert, AlertDescription } from "@/ui/alert";
import { Button } from "@/ui/button";
import { Checkbox } from "@/ui/checkbox";
import { Label } from "@/ui/label";
import { isObjectSpan } from "@/lib/traces";
import { useToast } from "@/ui/use-toast";
import { extractAssertions, packAssertions } from "@/lib/assertion-converters";
import AddEditDatasetDialog from "@/v2/pages-shared/datasets/AddEditDatasetDialog/AddEditDatasetDialog";
import AddEditTestSuiteDialog from "@/v2/pages-shared/datasets/AddEditTestSuiteDialog/AddEditTestSuiteDialog";
import ExplainerDescription from "@/shared/ExplainerDescription/ExplainerDescription";
import EvaluationCriteriaSection from "@/shared/EvaluationCriteriaSection/EvaluationCriteriaSection";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { usePermissions } from "@/contexts/PermissionsContext";
import LoadableSelectBox from "@/shared/LoadableSelectBox/LoadableSelectBox";
import { Separator } from "@/ui/separator";

const DEFAULT_SIZE = 100;

type EnrichmentOptions = {
  includeSpans: boolean;
  includeTags: boolean;
  includeFeedbackScores: boolean;
  includeComments: boolean;
  includeUsage: boolean;
  includeMetadata: boolean;
};

type AddToDatasetDialogProps = {
  selectedRows: Array<Trace | Span>;
  open: boolean;
  setOpen: (open: boolean) => void;
  datasetType: DATASET_TYPE;
};

const AddToDatasetDialog: React.FunctionComponent<AddToDatasetDialogProps> = ({
  selectedRows,
  open,
  setOpen,
  datasetType,
}) => {
  const isTestSuiteMode = datasetType === DATASET_TYPE.TEST_SUITE;
  const entityName = isTestSuiteMode ? "test suite" : "dataset";
  const noSelectionExplainerId = isTestSuiteMode
    ? EXPLAINER_ID.why_would_i_want_to_add_traces_to_an_test_suite
    : EXPLAINER_ID.whats_an_experiment;
  const successToastExplainerId = isTestSuiteMode
    ? EXPLAINER_ID.i_added_traces_to_an_test_suite_now_what
    : EXPLAINER_ID.i_added_items_to_a_dataset_now_what;
  const typeFilter = useMemo(
    () =>
      [
        {
          id: "type",
          field: "type",
          type: COLUMN_TYPE.string,
          operator: "=" as const,
          value: datasetType,
        },
      ] as Filter[],
    [datasetType],
  );
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const [openDialog, setOpenDialog] = useState<boolean>(false);
  const [fetching, setFetching] = useState<boolean>(false);
  const [selectedDataset, setSelectedDataset] = useState<Dataset | null>(null);
  const configSectionRef = useRef<HTMLDivElement>(null);
  const { toast } = useToast();
  const {
    permissions: { canCreateDatasets },
  } = usePermissions();

  // Enrichment options state - all checked by default (opt-out design)
  const [enrichmentOptions, setEnrichmentOptions] = useState({
    includeSpans: true,
    includeTags: true,
    includeFeedbackScores: true,
    includeComments: true,
    includeUsage: true,
    includeMetadata: true,
  });

  // Assertions state
  const [assertions, setAssertions] = useState<string[]>([]);
  const [runsPerItem, setRunsPerItem] = useState(
    DEFAULT_EXECUTION_POLICY.runs_per_item,
  );
  const [passThreshold, setPassThreshold] = useState(
    DEFAULT_EXECUTION_POLICY.pass_threshold,
  );
  const [useGlobalPolicy, setUseGlobalPolicy] = useState(true);

  const { mutate: addTracesToDataset } = useAddTracesToDatasetMutation();
  const { mutate: addSpansToDataset } = useAddSpansToDatasetMutation();

  const { data: versionsData } = useDatasetVersionsList(
    {
      datasetId: selectedDataset?.id ?? "",
      page: 1,
      size: 1,
    },
    {
      enabled: isTestSuiteMode && Boolean(selectedDataset?.id),
    },
  );

  const suiteAssertions = useMemo(() => {
    const evaluators = versionsData?.content?.[0]?.evaluators ?? [];
    return extractAssertions(evaluators);
  }, [versionsData]);

  const suiteExecutionPolicy = useMemo(() => {
    return (
      versionsData?.content?.[0]?.execution_policy ?? DEFAULT_EXECUTION_POLICY
    );
  }, [versionsData]);

  useEffect(() => {
    if (!isTestSuiteMode || !selectedDataset?.id) return;
    setRunsPerItem(suiteExecutionPolicy.runs_per_item);
    setPassThreshold(suiteExecutionPolicy.pass_threshold);
    setUseGlobalPolicy(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isTestSuiteMode, selectedDataset?.id]);

  const { data, isPending } = useProjectDatasetsList(
    {
      projectId: activeProjectId!,
      page: 1,
      size: DEFAULT_SIZE,
      filters: typeFilter,
    },
    {
      placeholderData: keepPreviousData,
      enabled: !!activeProjectId && open,
    },
  );

  const datasets = useMemo(() => data?.content ?? [], [data?.content]);

  const datasetOptions: DropdownOption<string>[] = useMemo(
    () =>
      datasets.map((d) => ({
        value: d.id,
        label: d.name,
        description: d.description,
      })),
    [datasets],
  );

  const datasetsById = useMemo(() => {
    const map = new Map<string, Dataset>();
    datasets.forEach((d) => map.set(d.id, d));
    return map;
  }, [datasets]);

  useEffect(() => {
    if (!isPending && datasets.length === 1 && !selectedDataset) {
      setSelectedDataset(datasets[0]);
    }
  }, [isPending, datasets, selectedDataset]);

  const emptyDropdownState = useMemo(
    () => (
      <div className="flex min-h-32 flex-col items-center justify-center gap-1 px-6 py-4 text-center">
        {isTestSuiteMode ? (
          <ListChecks className="mb-1 size-5 text-muted-slate" />
        ) : (
          <Database className="mb-1 size-5 text-muted-slate" />
        )}
        <span className="comet-body-s-accented">No {entityName}s yet</span>
        <span className="comet-body-xs text-muted-slate">
          {isTestSuiteMode
            ? "Define test cases with assertions to evaluate your LLM application's performance."
            : "Define inputs and expected outputs to evaluate your LLM application's performance."}
        </span>
      </div>
    ),
    [entityName, isTestSuiteMode],
  );

  const validRows = useMemo(() => {
    return selectedRows.filter((r) => !isUndefined(r.input));
  }, [selectedRows]);

  const validTraces = useMemo(() => {
    return validRows.filter((r) => !isObjectSpan(r));
  }, [validRows]);

  const validSpans = useMemo(() => {
    return validRows.filter((r) => isObjectSpan(r));
  }, [validRows]);

  const hasOnlyTraces = validTraces.length > 0 && validSpans.length === 0;
  const hasOnlySpans = validSpans.length > 0 && validTraces.length === 0;

  const noValidRows = validRows.length === 0;
  const partialValid = validRows.length !== selectedRows.length;

  const onItemsAdded = useCallback(
    (dataset: Dataset, hasTraces: boolean, hasSpans: boolean) => {
      let itemType = "Items";
      if (hasTraces && !hasSpans) {
        itemType = "Traces";
      } else if (hasSpans && !hasTraces) {
        itemType = "Spans";
      }

      toast({
        title: `${itemType} added to ${entityName}`,
        description: EXPLAINERS_MAP[successToastExplainerId].description,
      });
    },
    [toast, entityName, successToastExplainerId],
  );

  const handleAssertionChange = useCallback((index: number, value: string) => {
    setAssertions((prev) => prev.map((a, i) => (i === index ? value : a)));
  }, []);

  const handleAssertionRemove = useCallback((index: number) => {
    setAssertions((prev) => prev.filter((_, i) => i !== index));
  }, []);

  const handleAssertionAdd = useCallback(() => {
    setAssertions((prev) => ["", ...prev]);
  }, []);

  const handleRunsPerItemChange = useCallback(
    (v: number) => {
      setUseGlobalPolicy(false);
      setRunsPerItem(v);
      if (passThreshold > v) setPassThreshold(v);
    },
    [passThreshold],
  );

  const handlePassThresholdChange = useCallback((v: number) => {
    setUseGlobalPolicy(false);
    setPassThreshold(v);
  }, []);

  const handleRevertToDefaults = useCallback(() => {
    setUseGlobalPolicy(true);
    setRunsPerItem(suiteExecutionPolicy.runs_per_item);
    setPassThreshold(suiteExecutionPolicy.pass_threshold);
  }, [suiteExecutionPolicy]);

  const buildEvaluatorParams = useCallback(() => {
    const nonEmptyAssertions = assertions.map((a) => a.trim()).filter(Boolean);
    const hasCustomPolicy =
      runsPerItem !== suiteExecutionPolicy.runs_per_item ||
      passThreshold !== suiteExecutionPolicy.pass_threshold;

    if (nonEmptyAssertions.length === 0 && !hasCustomPolicy) return {};

    return {
      ...(nonEmptyAssertions.length > 0 && {
        evaluators: [packAssertions(nonEmptyAssertions)],
      }),
      ...(hasCustomPolicy && {
        executionPolicy: {
          runs_per_item: runsPerItem,
          pass_threshold: passThreshold,
        },
      }),
    };
  }, [assertions, runsPerItem, passThreshold, suiteExecutionPolicy]);

  const addToDatasetHandler = useCallback(
    (dataset: Dataset) => {
      setFetching(true);
      setOpen(false);

      const evalParams = isTestSuiteMode ? buildEvaluatorParams() : {};

      // If we have only traces, use the enriched endpoint for traces
      if (hasOnlyTraces) {
        addTracesToDataset(
          {
            workspaceName,
            datasetId: dataset.id,
            traceIds: validTraces.map((t) => t.id),
            enrichmentOptions: {
              include_spans: enrichmentOptions.includeSpans,
              include_tags: enrichmentOptions.includeTags,
              include_feedback_scores: enrichmentOptions.includeFeedbackScores,
              include_comments: enrichmentOptions.includeComments,
              include_usage: enrichmentOptions.includeUsage,
              include_metadata: enrichmentOptions.includeMetadata,
            },
            ...evalParams,
          },
          {
            onSuccess: () => {
              onItemsAdded(dataset, true, false);
              setFetching(false);
            },
            onError: () => {
              setFetching(false);
            },
          },
        );
      } else if (hasOnlySpans) {
        // If we have only spans, use the enriched endpoint for spans
        addSpansToDataset(
          {
            workspaceName,
            datasetId: dataset.id,
            spanIds: validSpans.map((s) => s.id),
            enrichmentOptions: {
              include_tags: enrichmentOptions.includeTags,
              include_feedback_scores: enrichmentOptions.includeFeedbackScores,
              include_comments: enrichmentOptions.includeComments,
              include_usage: enrichmentOptions.includeUsage,
              include_metadata: enrichmentOptions.includeMetadata,
            },
            ...evalParams,
          },
          {
            onSuccess: () => {
              onItemsAdded(dataset, false, true);
              setFetching(false);
            },
            onError: () => {
              setFetching(false);
            },
          },
        );
      }
    },
    [
      setOpen,
      addTracesToDataset,
      addSpansToDataset,
      workspaceName,
      onItemsAdded,
      enrichmentOptions,
      buildEvaluatorParams,
      hasOnlyTraces,
      hasOnlySpans,
      validTraces,
      validSpans,
      isTestSuiteMode,
    ],
  );

  const handleDatasetSelect = useCallback(
    (datasetId: string) => {
      const dataset = datasetsById.get(datasetId) ?? null;
      if (!dataset || dataset.id === selectedDataset?.id) return;
      setSelectedDataset(dataset);
      setAssertions([]);
      setRunsPerItem(DEFAULT_EXECUTION_POLICY.runs_per_item);
      setPassThreshold(DEFAULT_EXECUTION_POLICY.pass_threshold);
      setUseGlobalPolicy(true);
      setTimeout(() => {
        configSectionRef.current?.scrollIntoView({
          behavior: "smooth",
          block: "nearest",
        });
      }, 0);
    },
    [datasetsById, selectedDataset?.id],
  );

  const renderAlert = () => {
    const text = noValidRows
      ? `There are no rows that can be added as ${entityName} items. The input field is missing.`
      : `Only rows with input fields will be added as ${entityName} items.`;

    if (noValidRows || partialValid) {
      return (
        <Alert className="mt-4">
          <MessageCircleWarning />
          <AlertDescription>{text}</AlertDescription>
        </Alert>
      );
    }

    return null;
  };

  const renderEnrichmentCheckbox = (
    id: string,
    label: string,
    checked: boolean,
    field: keyof EnrichmentOptions,
  ) => (
    <div className="flex items-center space-x-2">
      <Checkbox
        id={id}
        checked={checked}
        onCheckedChange={(checked) =>
          setEnrichmentOptions((prev) => ({
            ...prev,
            [field]: checked === true,
          }))
        }
      />
      <Label htmlFor={id} className="comet-body-s cursor-pointer font-normal">
        {label}
      </Label>
    </div>
  );

  const renderMetadataConfiguration = (
    type: "trace" | "span",
    includeNestedSpans: boolean = false,
  ) => (
    <Accordion
      type="single"
      collapsible
      defaultValue="metadata"
      className="mb-4"
    >
      <AccordionItem value="metadata" className="border-t">
        <AccordionTrigger>
          {type === "trace"
            ? "Trace metadata configuration"
            : "Span metadata configuration"}
        </AccordionTrigger>
        <AccordionContent className="px-3">
          <div className="grid grid-cols-2 gap-3">
            {includeNestedSpans &&
              renderEnrichmentCheckbox(
                "include-spans",
                "Nested spans",
                enrichmentOptions.includeSpans,
                "includeSpans",
              )}
            {renderEnrichmentCheckbox(
              `include-tags${type === "span" ? "-span" : ""}`,
              "Tags",
              enrichmentOptions.includeTags,
              "includeTags",
            )}
            {renderEnrichmentCheckbox(
              `include-feedback-scores${type === "span" ? "-span" : ""}`,
              "Feedback scores",
              enrichmentOptions.includeFeedbackScores,
              "includeFeedbackScores",
            )}
            {renderEnrichmentCheckbox(
              `include-comments${type === "span" ? "-span" : ""}`,
              "Comments",
              enrichmentOptions.includeComments,
              "includeComments",
            )}
            {renderEnrichmentCheckbox(
              `include-usage${type === "span" ? "-span" : ""}`,
              "Usage metrics",
              enrichmentOptions.includeUsage,
              "includeUsage",
            )}
            {renderEnrichmentCheckbox(
              `include-metadata${type === "span" ? "-span" : ""}`,
              "Metadata",
              enrichmentOptions.includeMetadata,
              "includeMetadata",
            )}
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );

  return (
    <>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-lg sm:max-w-screen-sm">
          <DialogHeader>
            <DialogTitle>Add to {entityName}</DialogTitle>
          </DialogHeader>
          <DialogAutoScrollBody>
            {!selectedDataset && (
              <ExplainerDescription
                className="mb-4"
                {...EXPLAINERS_MAP[noSelectionExplainerId]}
              />
            )}
            <div className="my-2">
              <Label className="comet-body-s-accented mb-1">
                Select a {entityName}
              </Label>
              <LoadableSelectBox
                value={selectedDataset?.id ?? ""}
                onChange={handleDatasetSelect}
                options={datasetOptions}
                placeholder={
                  <div className="flex items-center gap-2">
                    {isTestSuiteMode ? (
                      <ListChecks className="size-4 shrink-0 text-muted-slate" />
                    ) : (
                      <Database className="size-4 shrink-0 text-muted-slate" />
                    )}
                    <span>Select a {entityName}</span>
                  </div>
                }
                renderTitle={(option: DropdownOption<string>) => (
                  <div className="flex items-center gap-2 truncate">
                    {isTestSuiteMode ? (
                      <ListChecks className="size-4 shrink-0 text-muted-slate" />
                    ) : (
                      <Database className="size-4 shrink-0 text-muted-slate" />
                    )}
                    <span className="truncate">{option.label}</span>
                  </div>
                )}
                searchPlaceholder={`Search ${entityName}s`}
                isLoading={isPending}
                disabled={noValidRows}
                buttonClassName="w-full"
                optionsCount={DEFAULT_SIZE}
                emptyState={emptyDropdownState}
                actionPanel={
                  canCreateDatasets ? (
                    <>
                      <Separator className="my-1" />
                      <div
                        className="flex h-10 cursor-pointer items-center gap-2 rounded-md px-4 hover:bg-primary-foreground"
                        onClick={() => setOpenDialog(true)}
                      >
                        <Plus className="size-4 shrink-0" />
                        <span className="comet-body-s">Add {entityName}</span>
                      </div>
                    </>
                  ) : undefined
                }
              />
            </div>
            {renderAlert()}
            {!isTestSuiteMode && selectedDataset && (
              <div ref={configSectionRef}>
                {hasOnlyTraces && renderMetadataConfiguration("trace", true)}
                {hasOnlySpans && renderMetadataConfiguration("span")}
              </div>
            )}
            {isTestSuiteMode && selectedDataset && (
              <div ref={configSectionRef} className="mt-6">
                <EvaluationCriteriaSection
                  suiteAssertions={suiteAssertions}
                  editableAssertions={assertions}
                  onChangeAssertion={handleAssertionChange}
                  onRemoveAssertion={handleAssertionRemove}
                  onAddAssertion={handleAssertionAdd}
                  runsPerItem={runsPerItem}
                  passThreshold={passThreshold}
                  onRunsPerItemChange={handleRunsPerItemChange}
                  onPassThresholdChange={handlePassThresholdChange}
                  useGlobalPolicy={useGlobalPolicy}
                  onRevertToDefaults={handleRevertToDefaults}
                  defaultRunsPerItem={suiteExecutionPolicy.runs_per_item}
                  defaultPassThreshold={suiteExecutionPolicy.pass_threshold}
                />
              </div>
            )}
          </DialogAutoScrollBody>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setOpen(false)}
              disabled={fetching}
            >
              Cancel
            </Button>
            <Button
              onClick={() => {
                if (selectedDataset) {
                  addToDatasetHandler(selectedDataset);
                }
              }}
              disabled={!selectedDataset || noValidRows || fetching}
            >
              Add to {entityName}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      {isTestSuiteMode ? (
        <AddEditTestSuiteDialog
          open={openDialog}
          setOpen={setOpenDialog}
          onDatasetCreated={(dataset) => {
            setSelectedDataset(dataset);
          }}
          hideUpload={true}
        />
      ) : (
        <AddEditDatasetDialog
          open={openDialog}
          setOpen={setOpenDialog}
          onDatasetCreated={(dataset) => {
            setSelectedDataset(dataset);
          }}
          hideUpload={true}
        />
      )}
    </>
  );
};

export default AddToDatasetDialog;
