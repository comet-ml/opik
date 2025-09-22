import React, { useCallback, useMemo, useState } from "react";
import { UserPen } from "lucide-react";
import { keepPreviousData } from "@tanstack/react-query";

import { Thread, Trace } from "@/types/traces";
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
import {
  ANNOTATION_QUEUE_SCOPE,
  AnnotationQueue,
} from "@/types/annotation-queues";
import { Button } from "@/components/ui/button";
import AddEditAnnotationQueueDialog from "@/components/pages-shared/annotation-queues/AddEditAnnotationQueueDialog";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { createFilter } from "@/lib/filters";
import { getAnnotationQueueItemId } from "@/lib/annotation-queues";
import { isObjectThread } from "@/lib/traces";

const DEFAULT_SIZE = 5;

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

  const addToQueueHandler = useCallback(
    (queue: AnnotationQueue) => {
      setOpen(false);
      mutate({
        annotationQueueId: queue.id,
        ids: rows.map(getAnnotationQueueItemId),
      });
    },
    [setOpen, mutate, rows],
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
        className="flex cursor-pointer flex-col rounded-sm px-4 py-2.5 hover:bg-muted"
        onClick={() => addToQueueHandler(q)}
      >
        <div className="flex flex-col gap-0.5">
          <div className="flex items-center gap-2">
            <UserPen className="size-4 shrink-0 text-muted-slate" />
            <span className="comet-body-s-accented w-full truncate text-muted-gray">
              {q.name}
            </span>
          </div>
          <div className="comet-body-s whitespace-pre-line break-words pl-6 text-light-slate">
            {q.instructions}
          </div>
        </div>
      </div>
    ));
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
              >
                Create new annotation queue
              </Button>
            </div>
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
