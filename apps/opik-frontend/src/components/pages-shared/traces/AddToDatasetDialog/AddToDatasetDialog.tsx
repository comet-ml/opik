import React, { useCallback, useMemo, useState } from "react";
import isUndefined from "lodash/isUndefined";
import { Database, MessageCircleWarning, Plus } from "lucide-react";
import { keepPreviousData } from "@tanstack/react-query";

import { Span, Trace } from "@/types/traces";
import useAppStore from "@/store/AppStore";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import Loader from "@/components/shared/Loader/Loader";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import useAddTracesToDatasetMutation from "@/api/datasets/useAddTracesToDatasetMutation";
import useAddSpansToDatasetMutation from "@/api/datasets/useAddSpansToDatasetMutation";
import { Dataset } from "@/types/datasets";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils";
import { isObjectSpan } from "@/lib/traces";
import { useToast } from "@/components/ui/use-toast";
import AddEditDatasetDialog from "@/components/pages/DatasetsPage/AddEditDatasetDialog";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { ToastAction } from "@/components/ui/toast";
import { useNavigateToExperiment } from "@/hooks/useNavigateToExperiment";

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
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(DEFAULT_SIZE);
  const [openDialog, setOpenDialog] = useState<boolean>(false);
  const [fetching, setFetching] = useState<boolean>(false);
  const [selectedDataset, setSelectedDataset] = useState<Dataset | null>(null);
  const { toast } = useToast();
  const { navigate } = useNavigateToExperiment();

  // Enrichment options state - all checked by default (opt-out design)
  const [enrichmentOptions, setEnrichmentOptions] = useState({
    includeSpans: true,
    includeTags: true,
    includeFeedbackScores: true,
    includeComments: true,
    includeUsage: true,
    includeMetadata: true,
  });

  const { mutate: addTracesToDataset } = useAddTracesToDatasetMutation();
  const { mutate: addSpansToDataset } = useAddSpansToDatasetMutation();

  const { data, isPending } = useDatasetsList(
    {
      workspaceName,
      search,
      page,
      size,
    },
    {
      placeholderData: keepPreviousData,
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
        EXPLAINERS_MAP[EXPLAINER_ID.i_added_traces_to_a_dataset_now_what];

      toast({
        title: `${itemType} added to dataset`,
        description: explainer.description,
        actions: [
          <ToastAction
            variant="link"
            size="sm"
            className="px-0"
            altText="Run an experiment"
            key="Run an experiment"
            onClick={() =>
              navigate({ newExperiment: true, datasetName: dataset.name })
            }
          >
            Run an experiment
          </ToastAction>,
        ],
      });
    },
    [navigate, toast],
  );

  const addToDatasetHandler = useCallback(
    (dataset: Dataset) => {
      setFetching(true);
      setOpen(false);

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
      const text = search ? "No search results" : "There are no datasets yet";

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
          onClick={() => !noValidRows && setSelectedDataset(d)}
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
      ? "There are no rows that can be added as dataset items. The input field is missing."
      : "Only rows with input fields will be added as dataset items.";

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
    <Accordion type="single" collapsible defaultValue="" className="mb-4">
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
            <DialogTitle>Add to dataset</DialogTitle>
          </DialogHeader>
          <DialogAutoScrollBody>
            <ExplainerDescription
              className="mb-4"
              {...EXPLAINERS_MAP[
                EXPLAINER_ID.why_would_i_want_to_add_traces_to_a_dataset
              ]}
            />
            {hasOnlyTraces && renderMetadataConfiguration("trace", true)}
            {hasOnlySpans && renderMetadataConfiguration("span")}
            <div className="my-2 flex items-center justify-between">
              <h3 className="comet-title-xs">Select a dataset</h3>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  setOpenDialog(true);
                }}
                disabled={noValidRows}
              >
                <Plus className="mr-2 size-4" />
                Create new dataset
              </Button>
            </div>
            <SearchInput
              searchText={search}
              setSearchText={setSearch}
              className="w-full"
            />
            {renderAlert()}
            <div className="my-4 flex max-h-[300px] min-h-36 max-w-full flex-col justify-stretch overflow-y-auto sm:max-h-[400px]">
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
              Add to dataset
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <AddEditDatasetDialog
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
