import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import isUndefined from "lodash/isUndefined";
import { Database, MessageCircleWarning, Plus } from "lucide-react";
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
import Loader from "@/shared/Loader/Loader";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import SearchInput from "@/shared/SearchInput/SearchInput";
import useAddTracesToDatasetMutation from "@/api/datasets/useAddTracesToDatasetMutation";
import useAddSpansToDatasetMutation from "@/api/datasets/useAddSpansToDatasetMutation";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import { Dataset, DATASET_TYPE } from "@/types/datasets";
import {
  DEFAULT_EXECUTION_POLICY,
  MAX_RUNS_PER_ITEM,
} from "@/types/evaluation-suites";
import { Alert, AlertDescription } from "@/ui/alert";
import { Button } from "@/ui/button";
import { Checkbox } from "@/ui/checkbox";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { cn } from "@/lib/utils";
import { isObjectSpan } from "@/lib/traces";
import { useToast } from "@/ui/use-toast";
import { extractAssertions, packAssertions } from "@/lib/assertion-converters";
import { useClampedIntegerInput } from "@/hooks/useClampedIntegerInput";
import AssertionsField from "@/shared/AssertionField/AssertionsField";
import AddEditEvaluationSuiteDialog from "@/v2/pages-shared/datasets/AddEditEvaluationSuiteDialog/AddEditEvaluationSuiteDialog";
import ExplainerDescription from "@/shared/ExplainerDescription/ExplainerDescription";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { usePermissions } from "@/contexts/PermissionsContext";

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
};

const AddToDatasetDialog: React.FunctionComponent<AddToDatasetDialogProps> = ({
  selectedRows,
  open,
  setOpen,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(DEFAULT_SIZE);
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

  const { mutate: addTracesToDataset } = useAddTracesToDatasetMutation();
  const { mutate: addSpansToDataset } = useAddSpansToDatasetMutation();

  // Fetch the latest version of the selected dataset to get suite-level assertions
  const { data: versionsData } = useDatasetVersionsList(
    {
      datasetId: selectedDataset?.id ?? "",
      page: 1,
      size: 1,
    },
    {
      enabled: Boolean(selectedDataset?.id),
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

  // Sync form state with suite defaults when selected dataset changes or version data loads
  useEffect(() => {
    if (!selectedDataset?.id) return;
    setRunsPerItem(suiteExecutionPolicy.runs_per_item);
    setPassThreshold(suiteExecutionPolicy.pass_threshold);
  }, [selectedDataset?.id, suiteExecutionPolicy]);

  const { data, isPending } = useProjectDatasetsList(
    {
      projectId: activeProjectId!,
      search,
      page,
      size,
    },
    {
      placeholderData: keepPreviousData,
      enabled: !!activeProjectId,
    },
  );

  const datasets = data?.content ?? [];
  const total = data?.total ?? 0;

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

      const explainer =
        EXPLAINERS_MAP[
          EXPLAINER_ID.i_added_traces_to_an_evaluation_suite_now_what
        ];

      toast({
        title: `${itemType} added to evaluation suite`,
        description: explainer.description,
      });
    },
    [toast],
  );

  const handleAssertionChange = useCallback((index: number, value: string) => {
    setAssertions((prev) => prev.map((a, i) => (i === index ? value : a)));
  }, []);

  const handleAssertionRemove = useCallback((index: number) => {
    setAssertions((prev) => prev.filter((_, i) => i !== index));
  }, []);

  const handleAssertionAdd = useCallback(() => {
    setAssertions((prev) => [...prev, ""]);
  }, []);

  const runsInput = useClampedIntegerInput({
    value: runsPerItem,
    min: 1,
    max: MAX_RUNS_PER_ITEM,
    onCommit: (v) => {
      setRunsPerItem(v);
      if (passThreshold > v) setPassThreshold(v);
    },
  });

  const thresholdInput = useClampedIntegerInput({
    value: passThreshold,
    min: 1,
    max: runsPerItem,
    onCommit: setPassThreshold,
  });

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

      const evalParams = buildEvaluatorParams();

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
    ],
  );

  const renderListItems = () => {
    if (isPending || fetching) {
      return <Loader />;
    }

    if (datasets.length === 0) {
      const text = search
        ? "No search results"
        : "There are no evaluation suites yet";

      return (
        <div className="comet-body-s flex h-32 items-center justify-center text-muted-slate">
          {text}
        </div>
      );
    }

    return datasets.map((d) => {
      const isSelected = selectedDataset?.id === d.id;
      return (
        <div
          key={d.id}
          className={cn(
            "rounded-sm px-4 py-2.5 flex flex-col",
            noValidRows
              ? "cursor-default"
              : "cursor-pointer hover:bg-primary-foreground",
            isSelected && "bg-muted",
          )}
          onClick={() => {
            if (noValidRows || d.id === selectedDataset?.id) return;
            setSelectedDataset(d);
            setAssertions([]);
            setRunsPerItem(DEFAULT_EXECUTION_POLICY.runs_per_item);
            setPassThreshold(DEFAULT_EXECUTION_POLICY.pass_threshold);
            setTimeout(() => {
              configSectionRef.current?.scrollIntoView({
                behavior: "smooth",
                block: "nearest",
              });
            }, 0);
          }}
        >
          <div className="flex flex-col gap-0.5">
            <div className="flex items-center gap-2">
              <Database
                className={cn(
                  "size-4 shrink-0",
                  noValidRows ? "text-muted-gray" : "text-muted-slate",
                )}
              />
              <span
                className={cn(
                  "comet-body-s-accented truncate w-full",
                  noValidRows && "text-muted-gray",
                )}
              >
                {d.name}
              </span>
            </div>
            <div
              className={cn(
                "comet-body-s pl-6 whitespace-pre-line break-words",
                noValidRows ? "text-muted-gray" : "text-light-slate",
              )}
            >
              {d.description}
            </div>
          </div>
        </div>
      );
    });
  };

  const renderAlert = () => {
    const text = noValidRows
      ? "There are no rows that can be added as evaluation suite items. The input field is missing."
      : "Only rows with input fields will be added as evaluation suite items.";

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
        <DialogContent className="max-w-lg sm:max-w-[560px]">
          <DialogHeader>
            <DialogTitle>Add to evaluation suite</DialogTitle>
          </DialogHeader>
          <DialogAutoScrollBody>
            {!selectedDataset && (
              <ExplainerDescription
                className="mb-4"
                {...EXPLAINERS_MAP[
                  EXPLAINER_ID
                    .why_would_i_want_to_add_traces_to_an_evaluation_suite
                ]}
              />
            )}
            <div className="my-2 flex items-center justify-between">
              <h3 className="comet-title-xs">Select an evaluation suite</h3>
              {canCreateDatasets && (
                <Button
                  variant="ghost"
                  size="xs"
                  onClick={() => {
                    setOpenDialog(true);
                  }}
                  disabled={noValidRows}
                >
                  <Plus className="mr-1 size-4" />
                  Create evaluation suite
                </Button>
              )}
            </div>
            <SearchInput
              searchText={search}
              setSearchText={setSearch}
              className="w-full"
            />
            {renderAlert()}
            <div className="my-4 flex max-h-[225px] min-h-36 max-w-full flex-col justify-stretch overflow-y-auto">
              {renderListItems()}
            </div>
            {total > DEFAULT_SIZE && (
              <div className="pt-4">
                <DataTablePagination
                  page={page}
                  pageChange={setPage}
                  size={size}
                  sizeChange={setSize}
                  total={total}
                ></DataTablePagination>
              </div>
            )}
            {selectedDataset?.type === DATASET_TYPE.DATASET && (
              <div ref={configSectionRef}>
                {hasOnlyTraces && renderMetadataConfiguration("trace", true)}
                {hasOnlySpans && renderMetadataConfiguration("span")}
              </div>
            )}
            {selectedDataset?.type === DATASET_TYPE.EVALUATION_SUITE && (
              <Accordion
                ref={configSectionRef}
                type="single"
                collapsible
                defaultValue="assertions"
                className="mt-2"
              >
                <AccordionItem value="assertions" className="border-t">
                  <AccordionTrigger>Evaluation criteria</AccordionTrigger>
                  <AccordionContent className="px-3">
                    <div className="flex flex-col gap-4">
                      <div className="flex flex-col gap-1">
                        <span className="comet-body-s-accented">
                          Assertions
                        </span>
                        <span className="comet-body-xs text-light-slate">
                          Define the conditions for this evaluation to pass
                        </span>
                        <AssertionsField
                          readOnlyAssertions={suiteAssertions}
                          editableAssertions={assertions}
                          onChangeEditable={handleAssertionChange}
                          onRemoveEditable={handleAssertionRemove}
                          onAdd={handleAssertionAdd}
                        />
                      </div>
                      <div className="flex gap-4">
                        <div className="flex flex-1 flex-col gap-1">
                          <Label className="comet-body-xs-accented">
                            Runs per item
                          </Label>
                          <Input
                            dimension="sm"
                            className="[&::-webkit-inner-spin-button]:appearance-none"
                            type="number"
                            min={1}
                            max={MAX_RUNS_PER_ITEM}
                            value={runsInput.displayValue}
                            onChange={runsInput.onChange}
                            onFocus={runsInput.onFocus}
                            onBlur={runsInput.onBlur}
                            onKeyDown={runsInput.onKeyDown}
                          />
                          <span className="comet-body-xs text-light-slate">
                            Suite default: {suiteExecutionPolicy.runs_per_item}
                          </span>
                        </div>
                        <div className="flex flex-1 flex-col gap-1">
                          <Label className="comet-body-xs-accented">
                            Pass threshold
                          </Label>
                          <Input
                            dimension="sm"
                            className="[&::-webkit-inner-spin-button]:appearance-none"
                            type="number"
                            min={1}
                            max={runsPerItem}
                            value={thresholdInput.displayValue}
                            onChange={thresholdInput.onChange}
                            onFocus={thresholdInput.onFocus}
                            onBlur={thresholdInput.onBlur}
                            onKeyDown={thresholdInput.onKeyDown}
                          />
                          <span className="comet-body-xs text-light-slate">
                            Suite default: {suiteExecutionPolicy.pass_threshold}
                          </span>
                        </div>
                      </div>
                    </div>
                  </AccordionContent>
                </AccordionItem>
              </Accordion>
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
              Add to evaluation suite
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <AddEditEvaluationSuiteDialog
        open={openDialog}
        setOpen={setOpenDialog}
        onDatasetCreated={(dataset) => {
          setSelectedDataset(dataset);
        }}
        hideUpload={true}
      />
    </>
  );
};

export default AddToDatasetDialog;
