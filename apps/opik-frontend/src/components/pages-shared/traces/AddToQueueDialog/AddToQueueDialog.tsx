import React, { useCallback, useMemo, useState } from "react";
import get from "lodash/get";
import isUndefined from "lodash/isUndefined";
import { Users, MessageCircleWarning } from "lucide-react";
import { keepPreviousData } from "@tanstack/react-query";

import { Span, Trace } from "@/types/traces";
import useAppStore from "@/store/AppStore";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import useAnnotationQueuesList from "@/api/annotation-queues/useAnnotationQueuesList";
import Loader from "@/components/shared/Loader/Loader";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import useAnnotationQueueAddItemsMutation from "@/api/annotation-queues/useAnnotationQueueAddItemsMutation";
import { AnnotationQueue } from "@/types/annotation-queues";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { isObjectSpan } from "@/lib/traces";
import { useToast } from "@/components/ui/use-toast";
import AddEditAnnotationQueueDialog from "@/components/pages-shared/annotation-queues/AddEditAnnotationQueueDialog";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

const DEFAULT_SIZE = 5;

type AddToQueueDialogProps = {
  rows: Array<Trace | Span>;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const AddToQueueDialog: React.FunctionComponent<AddToQueueDialogProps> = ({
  rows,
  open,
  setOpen,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(DEFAULT_SIZE);
  const [openDialog, setOpenDialog] = useState<boolean>(false);
  const { toast } = useToast();

  const { mutate } = useAnnotationQueueAddItemsMutation();

  const { data, isPending } = useAnnotationQueuesList(
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

  const queues = data?.content ?? [];
  const total = data?.total ?? 0;

  const validRows = useMemo(() => {
    return rows.filter((r) => !isUndefined(r.input));
  }, [rows]);

  const noValidRows = validRows.length === 0;
  const partialValid = validRows.length !== rows.length;

  const onItemsAdded = useCallback(() => {
    const explainer = EXPLAINERS_MAP[EXPLAINER_ID.what_are_annotation_queues];

    toast({
      title: "Items added to annotation queue",
      description: explainer.description,
    });
  }, [toast]);

  const addToQueueHandler = useCallback(
    (queue: AnnotationQueue) => {
      setOpen(false);
      mutate(
        {
          annotationQueueId: queue.id,
          ids: validRows.map((r) => {
            const isSpan = isObjectSpan(r);
            const spanId = isSpan ? r.id : "";
            const traceId = isSpan ? get(r, "trace_id", "") : r.id;

            // Return the appropriate ID based on the row type
            return isSpan ? spanId : traceId;
          }),
        },
        {
          onSuccess: () => onItemsAdded(),
        },
      );
    },
    [setOpen, mutate, validRows, onItemsAdded],
  );

  const onQueueCreated = useCallback(
    (queue: Partial<AnnotationQueue>) => {
      if (queue.id && queue.name) {
        addToQueueHandler(queue as AnnotationQueue);
      }
    },
    [addToQueueHandler],
  );

  const renderListItems = () => {
    if (isPending) {
      return <Loader />;
    }

    if (queues.length === 0) {
      const text = search
        ? "No search results"
        : "There are no annotation queues yet";

      return (
        <div className="comet-body-s flex h-32 items-center justify-center text-muted-slate">
          {text}
        </div>
      );
    }

    return queues.map((q) => (
      <div
        key={q.id}
        className={cn(
          "rounded-sm px-4 py-2.5 flex flex-col",
          noValidRows ? "cursor-default" : "cursor-pointer hover:bg-muted",
        )}
        onClick={() => !noValidRows && addToQueueHandler(q)}
      >
        <div className="flex flex-col gap-0.5">
          <div className="flex items-center gap-2">
            <Users
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
              {q.name}
            </span>
          </div>
          <div
            className={cn(
              "comet-body-s pl-6 whitespace-pre-line break-words",
              noValidRows ? "text-muted-gray" : "text-light-slate",
            )}
          >
            {q.description}
          </div>
        </div>
      </div>
    ));
  };

  const renderAlert = () => {
    const text = noValidRows
      ? "There are no rows that can be added to annotation queues. The input field is missing."
      : "Only rows with input fields will be added to annotation queues.";

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
            <DialogTitle>Add to annotation queue</DialogTitle>
          </DialogHeader>
          <div className="w-full overflow-hidden">
            <ExplainerDescription
              className="mb-4"
              {...EXPLAINERS_MAP[EXPLAINER_ID.what_are_annotation_queues]}
            />
            <div className="flex gap-2.5">
              <SearchInput
                searchText={search}
                setSearchText={setSearch}
              ></SearchInput>
              <Button
                variant="secondary"
                onClick={() => {
                  setOpen(false);
                  setOpenDialog(true);
                }}
                disabled={noValidRows}
              >
                Create new annotation queue
              </Button>
            </div>
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
      <AddEditAnnotationQueueDialog
        open={openDialog}
        setOpen={setOpenDialog}
        onQueueCreated={onQueueCreated}
      />
    </>
  );
};

export default AddToQueueDialog;
