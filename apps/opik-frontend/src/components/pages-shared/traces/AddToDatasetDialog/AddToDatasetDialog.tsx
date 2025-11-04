import React, { useCallback, useMemo, useState } from "react";
import get from "lodash/get";
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
import useDatasetItemBatchMutation from "@/api/datasets/useDatasetItemBatchMutation";
import useAddTracesToDatasetMutation from "@/api/datasets/useAddTracesToDatasetMutation";
import { Dataset, DATASET_ITEM_SOURCE } from "@/types/datasets";
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

type AddToDatasetDialogProps = {
  getDataForExport: () => Promise<Array<Trace | Span>>;
  selectedRows: Array<Trace | Span>;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const AddToDatasetDialog: React.FunctionComponent<AddToDatasetDialogProps> = ({
  getDataForExport,
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

  const { mutate } = useDatasetItemBatchMutation();
  const { mutate: addTracesToDataset } = useAddTracesToDatasetMutation();

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
    async (dataset: Dataset) => {
      setFetching(true);
      setOpen(false);
      try {
        const rows = await getDataForExport();
        const validRowsFromFetch = rows.filter((r) => !isUndefined(r.input));

        const traces = validRowsFromFetch.filter((r) => !isObjectSpan(r));
        const spans = validRowsFromFetch.filter((r) => isObjectSpan(r));

        // If we have only traces, use the new enriched endpoint
        if (traces.length > 0 && spans.length === 0) {
          addTracesToDataset(
            {
              workspaceName,
              datasetId: dataset.id,
              traceIds: traces.map((t) => t.id),
              enrichmentOptions: {
                include_spans: enrichmentOptions.includeSpans,
                include_tags: enrichmentOptions.includeTags,
                include_feedback_scores:
                  enrichmentOptions.includeFeedbackScores,
                include_comments: enrichmentOptions.includeComments,
                include_usage: enrichmentOptions.includeUsage,
                include_metadata: enrichmentOptions.includeMetadata,
              },
            },
            {
              onSuccess: () => onItemsAdded(dataset, true, false),
            },
          );
        } else {
          // For spans or mixed content, use the old endpoint
          mutate(
            {
              workspaceName,
              datasetId: dataset.id,
              datasetItems: validRowsFromFetch.map((r) => {
                const isSpan = isObjectSpan(r);
                const spanId = isSpan ? r.id : "";
                const traceId = isSpan ? get(r, "trace_id", "") : r.id;

                return {
                  data: {
                    input: r.input,
                    ...(r.output && { expected_output: r.output }),
                  },
                  source: isSpan
                    ? DATASET_ITEM_SOURCE.span
                    : DATASET_ITEM_SOURCE.trace,
                  trace_id: traceId,
                  ...(isSpan ? { span_id: spanId } : {}),
                };
              }),
            },
            {
              onSuccess: () =>
                onItemsAdded(dataset, traces.length > 0, spans.length > 0),
            },
          );
        }
      } catch (error) {
        const message = get(
          error,
          ["response", "data", "message"],
          get(error, "message", "Failed to fetch data for adding to dataset"),
        );
        toast({
          title: "Failed to fetch data",
          description: message,
          variant: "destructive",
        });
      } finally {
        setFetching(false);
      }
    },
    [
      getDataForExport,
      setOpen,
      mutate,
      addTracesToDataset,
      workspaceName,
      onItemsAdded,
      toast,
      enrichmentOptions,
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
            {hasOnlyTraces && (
              <Accordion
                type="single"
                collapsible
                defaultValue=""
                className="mb-4"
              >
                <AccordionItem value="metadata" className="border-t">
                  <AccordionTrigger>
                    Trace metadata configuration
                  </AccordionTrigger>
                  <AccordionContent className="px-3">
                    <div className="grid grid-cols-2 gap-3">
                      <div className="flex items-center space-x-2">
                        <Checkbox
                          id="include-spans"
                          checked={enrichmentOptions.includeSpans}
                          onCheckedChange={(checked) =>
                            setEnrichmentOptions((prev) => ({
                              ...prev,
                              includeSpans: checked === true,
                            }))
                          }
                        />
                        <Label
                          htmlFor="include-spans"
                          className="comet-body-s cursor-pointer font-normal"
                        >
                          Nested spans
                        </Label>
                      </div>
                      <div className="flex items-center space-x-2">
                        <Checkbox
                          id="include-tags"
                          checked={enrichmentOptions.includeTags}
                          onCheckedChange={(checked) =>
                            setEnrichmentOptions((prev) => ({
                              ...prev,
                              includeTags: checked === true,
                            }))
                          }
                        />
                        <Label
                          htmlFor="include-tags"
                          className="comet-body-s cursor-pointer font-normal"
                        >
                          Tags
                        </Label>
                      </div>
                      <div className="flex items-center space-x-2">
                        <Checkbox
                          id="include-feedback-scores"
                          checked={enrichmentOptions.includeFeedbackScores}
                          onCheckedChange={(checked) =>
                            setEnrichmentOptions((prev) => ({
                              ...prev,
                              includeFeedbackScores: checked === true,
                            }))
                          }
                        />
                        <Label
                          htmlFor="include-feedback-scores"
                          className="comet-body-s cursor-pointer font-normal"
                        >
                          Feedback scores
                        </Label>
                      </div>
                      <div className="flex items-center space-x-2">
                        <Checkbox
                          id="include-comments"
                          checked={enrichmentOptions.includeComments}
                          onCheckedChange={(checked) =>
                            setEnrichmentOptions((prev) => ({
                              ...prev,
                              includeComments: checked === true,
                            }))
                          }
                        />
                        <Label
                          htmlFor="include-comments"
                          className="comet-body-s cursor-pointer font-normal"
                        >
                          Comments
                        </Label>
                      </div>
                      <div className="flex items-center space-x-2">
                        <Checkbox
                          id="include-usage"
                          checked={enrichmentOptions.includeUsage}
                          onCheckedChange={(checked) =>
                            setEnrichmentOptions((prev) => ({
                              ...prev,
                              includeUsage: checked === true,
                            }))
                          }
                        />
                        <Label
                          htmlFor="include-usage"
                          className="comet-body-s cursor-pointer font-normal"
                        >
                          Usage metrics
                        </Label>
                      </div>
                      <div className="flex items-center space-x-2">
                        <Checkbox
                          id="include-metadata"
                          checked={enrichmentOptions.includeMetadata}
                          onCheckedChange={(checked) =>
                            setEnrichmentOptions((prev) => ({
                              ...prev,
                              includeMetadata: checked === true,
                            }))
                          }
                        />
                        <Label
                          htmlFor="include-metadata"
                          className="comet-body-s cursor-pointer font-normal"
                        >
                          Metadata
                        </Label>
                      </div>
                    </div>
                  </AccordionContent>
                </AccordionItem>
              </Accordion>
            )}
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
