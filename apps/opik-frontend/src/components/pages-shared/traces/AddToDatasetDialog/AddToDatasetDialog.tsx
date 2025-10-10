import React, { useCallback, useMemo, useState } from "react";
import get from "lodash/get";
import isUndefined from "lodash/isUndefined";
import { Database, MessageCircleWarning, Plus } from "lucide-react";
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
    return selectedRows.filter((r) => !isUndefined(r.input));
  }, [selectedRows]);

  const noValidRows = validRows.length === 0;
  const partialValid = validRows.length !== selectedRows.length;

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
    async (dataset: Dataset) => {
      setFetching(true);
      setOpen(false);
      try {
        const rows = await getDataForExport();
        const validRowsFromFetch = rows.filter((r) => !isUndefined(r.input));

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
            onSuccess: () => onItemsAdded(dataset),
          },
        );
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
    [getDataForExport, setOpen, mutate, workspaceName, onItemsAdded, toast],
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
            <ExplainerDescription
              className="mb-4"
              {...EXPLAINERS_MAP[
                EXPLAINER_ID.why_would_i_want_to_add_traces_to_a_dataset
              ]}
            />
            <div className="my-2 flex items-center justify-between">
              <h3 className="comet-title-xs">Select a dataset</h3>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  setOpen(false);
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
            <div className="my-4 flex max-h-[400px] min-h-36 max-w-full flex-col justify-stretch overflow-y-auto">
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
          </div>
        </DialogContent>
      </Dialog>
      <AddEditDatasetDialog
        open={openDialog}
        setOpen={setOpenDialog}
        onDatasetCreated={addToDatasetHandler}
        hideUpload={true}
      />
    </>
  );
};

export default AddToDatasetDialog;
