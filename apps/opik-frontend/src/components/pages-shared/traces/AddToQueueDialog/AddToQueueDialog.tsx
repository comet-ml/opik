import React, { useCallback, useMemo, useState } from "react";
import { UserPen, MessageCircleWarning, Plus } from "lucide-react";
import { keepPreviousData } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";

import { Thread, Trace } from "@/types/traces";
import { ThreadStatus } from "@/types/thread";
import useAppStore from "@/store/AppStore";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { useToast } from "@/components/ui/use-toast";
import { ToastAction } from "@/components/ui/toast";
import useAnnotationQueuesList from "@/api/annotation-queues/useAnnotationQueuesList";
import Loader from "@/components/shared/Loader/Loader";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import useAnnotationQueueAddItemsMutation from "@/api/annotation-queues/useAnnotationQueueAddItemsMutation";
import {
  ANNOTATION_QUEUE_SCOPE,
  AnnotationQueue,
} from "@/types/annotation-queues";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { cn } from "@/lib/utils";
import AddEditAnnotationQueueDialog from "@/components/pages-shared/annotation-queues/AddEditAnnotationQueueDialog";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { createFilter } from "@/lib/filters";
import { getAnnotationQueueItemId } from "@/lib/annotation-queues";
import { isObjectThread } from "@/lib/traces";

const DEFAULT_SIZE = 100;

type AddToQueueDialogProps = {
  rows: Array<Trace | Thread>;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const AddToQueueDialog: React.FunctionComponent<AddToQueueDialogProps> = ({
  rows,
  open,
  setOpen,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { toast } = useToast();
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(DEFAULT_SIZE);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const { mutate } = useAnnotationQueueAddItemsMutation();

  const scope = isObjectThread(rows[0])
    ? ANNOTATION_QUEUE_SCOPE.THREAD
    : ANNOTATION_QUEUE_SCOPE.TRACE;

  const projectId = rows?.[0]?.project_id;

  const filters = useMemo(
    () => [
      createFilter({
        field: "scope",
        value: scope,
        operator: "=",
      }),
    ],
    [scope],
  );

  const { data, isPending } = useAnnotationQueuesList(
    {
      workspaceName,
      projectId: projectId,
      filters,
      search,
      page,
      size,
    },
    {
      placeholderData: keepPreviousData,
      enabled: Boolean(projectId),
    },
  );

  const queues = data?.content ?? [];
  const total = data?.total ?? 0;

  const validRows = useMemo(
    () =>
      rows.filter(
        (r) =>
          !isObjectThread(r) || (r as Thread).status === ThreadStatus.INACTIVE,
      ),
    [rows],
  );

  const noValidRows = validRows.length === 0;
  const partialValid = validRows.length !== rows.length;

  const handleAddSuccess = useCallback(
    (queue: AnnotationQueue) => {
      toast({
        title: "Items added to annotation queue",
        description:
          "Start annotating your items and provide feedback to improve the evaluation of your LLM application.",
        actions: [
          <ToastAction
            variant="link"
            size="sm"
            className="px-0"
            altText="Go to annotation queue"
            key="Go to annotation queue"
            onClick={() =>
              navigate({
                to: "/$workspaceName/annotation-queues/$annotationQueueId",
                params: {
                  workspaceName,
                  annotationQueueId: queue.id,
                },
              })
            }
          >
            Go to annotation queue
          </ToastAction>,
        ],
      });
    },
    [toast, navigate, workspaceName],
  );

  const addToQueueHandler = useCallback(
    (queue: AnnotationQueue) => {
      setOpen(false);
      mutate(
        {
          annotationQueueId: queue.id,
          ids: validRows.map(getAnnotationQueueItemId),
        },
        {
          onSuccess: () => handleAddSuccess(queue),
        },
      );
    },
    [setOpen, mutate, validRows, handleAddSuccess],
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
        : "There are no annotation queues in this project yet";

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
            <UserPen
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
            {q.instructions}
          </div>
        </div>
      </div>
    ));
  };

  const renderAlert = () => {
    const text = noValidRows
      ? "There are no rows that can be added to annotation queues. Only traces and inactive threads can be added."
      : "Only traces and inactive threads will be added to annotation queues. Active threads will be excluded.";

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
            <div className="my-2 flex items-center justify-between">
              <h3 className="comet-title-xs">Select an annotation queue</h3>
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
                Create new annotation queue
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
      <AddEditAnnotationQueueDialog
        open={openDialog}
        setOpen={setOpenDialog}
        onQueueCreated={onQueueCreated}
        projectId={projectId}
        scope={scope}
      />
    </>
  );
};

export default AddToQueueDialog;
