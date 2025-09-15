import React, { useCallback, useMemo, useState } from "react";
import get from "lodash/get";
import isUndefined from "lodash/isUndefined";
import { Database, MessageCircleWarning, Info } from "lucide-react";
import { keepPreviousData } from "@tanstack/react-query";

import { Span, Trace } from "@/types/traces";
import useAppStore from "@/store/AppStore";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import Loader from "@/components/shared/Loader/Loader";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import useDatasetItemBatchMutation from "@/api/datasets/useDatasetItemBatchMutation";
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
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const DEFAULT_SIZE = 5;

type AddToDatasetDialogProps = {
  rows: Array<Trace | Span>;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const AddToDatasetDialog: React.FunctionComponent<AddToDatasetDialogProps> = ({
  rows,
  open,
  setOpen,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(DEFAULT_SIZE);
  const [openDialog, setOpenDialog] = useState<boolean>(false);
  const [includeTraceMetadata, setIncludeTraceMetadata] = useState(false);
  const { toast } = useToast();
  const { navigate } = useNavigateToExperiment();

  const { mutate } = useDatasetItemBatchMutation();

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
    return rows.filter((r) => !isUndefined(r.input));
  }, [rows]);

  const noValidRows = validRows.length === 0;
  const partialValid = validRows.length !== rows.length;

  const onItemsAdded = useCallback(
    (dataset: Dataset) => {
      const explainer =
        EXPLAINERS_MAP[EXPLAINER_ID.i_added_traces_to_a_dataset_now_what];

      toast({
        title: explainer.title,
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
      setOpen(false);
      mutate(
        {
          workspaceName,
          datasetId: dataset.id,
          datasetItems: validRows.map((r) => {
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
          includeTraceMetadata,
        },
        {
          onSuccess: () => onItemsAdded(dataset),
        },
      );
    },
    [setOpen, mutate, workspaceName, validRows, onItemsAdded, includeTraceMetadata],
  );

  const renderListItems = () => {
    if (isPending) {
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

    return datasets.map((d) => (
      <div
        key={d.id}
        className={cn(
          "rounded-sm px-4 py-2.5 flex flex-col",
          noValidRows ? "cursor-default" : "cursor-pointer hover:bg-muted",
        )}
        onClick={() => !noValidRows && addToDatasetHandler(d)}
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
    ));
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
        <DialogContent className="max-w-lg pb-8 sm:max-w-[560px]">
          <DialogHeader>
            <DialogTitle>Add to dataset</DialogTitle>
          </DialogHeader>
          <div className="w-full overflow-hidden">
            <div className="mb-6">
              <p className="comet-body-s text-muted-slate mb-4">
                Select a dataset to add traces. Optionally include trace metadata. You can then use the dataset in experiments to measure and track your LLM app's performance over time.
              </p>
              
              {/* Options Section */}
              <div className="mb-6">
                <h4 className="comet-body-s-accented text-foreground mb-3">Options</h4>
                <div className="flex items-center space-x-2">
                  <Checkbox
                    id="include-metadata"
                    checked={includeTraceMetadata}
                    onCheckedChange={(checked) => setIncludeTraceMetadata(checked as boolean)}
                  />
                  <Label htmlFor="include-metadata" className="text-sm font-medium cursor-pointer">
                    Include trace metadata
                  </Label>
                  <TooltipWrapper content="Adds tags, comments, and feedback scores">
                    <Info className="size-4 text-light-slate" />
                  </TooltipWrapper>
                </div>
              </div>

              {renderAlert()}
              
              {/* Datasets Section */}
              <div>
                <h4 className="comet-body-s-accented text-foreground mb-3">Datasets</h4>
                <div className="flex gap-2.5 mb-4">
                  <SearchInput
                    searchText={search}
                    setSearchText={setSearch}
                    placeholder="Search datasets..."
                  />
                  <Button
                    variant="secondary"
                    onClick={() => {
                      setOpen(false);
                      setOpenDialog(true);
                    }}
                    disabled={noValidRows}
                  >
                    Create new dataset
                  </Button>
                </div>
                
                <div className="flex max-h-[300px] min-h-32 max-w-full flex-col justify-stretch overflow-y-auto border rounded-md">
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
                    />
                  </div>
                )}
              </div>
            </div>
          </div>
        </DialogContent>
      </Dialog>
      <AddEditDatasetDialog
        open={openDialog}
        setOpen={setOpenDialog}
        onDatasetCreated={addToDatasetHandler}
      />
    </>
  );
};

export default AddToDatasetDialog;
