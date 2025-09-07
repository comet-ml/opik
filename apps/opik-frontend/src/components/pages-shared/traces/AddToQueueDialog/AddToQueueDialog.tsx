import React, { useCallback, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { Users } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { useToast } from "@/components/ui/use-toast";
import { ToastAction } from "@/components/ui/toast";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import Loader from "@/components/shared/Loader/Loader";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import { cn } from "@/lib/utils";

import { Span, Trace, Thread } from "@/types/traces";
import { AnnotationQueue, AnnotationQueueScope } from "@/types/annotation-queues";
import useAppStore from "@/store/AppStore";
import useAnnotationQueuesList from "@/api/annotation-queues/useAnnotationQueuesList";
import { useAnnotationQueueItemsAddMutation } from "@/api/annotation-queues/useAnnotationQueueItemsMutation";
import CreateAnnotationQueueDialog from "@/components/pages/AnnotationQueuesPage/CreateAnnotationQueueDialog";

const DEFAULT_SIZE = 5;

type AddToQueueDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  rows: Array<Trace | Span | Thread>;
  type: "traces" | "spans" | "threads";
};

const AddToQueueDialog: React.FunctionComponent<AddToQueueDialogProps> = ({
  open,
  setOpen,
  rows,
  type,
}) => {
  const { toast } = useToast();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [searchTerm, setSearchTerm] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(1); // Show only 1 queue at a time
  const [showCreateDialog, setShowCreateDialog] = useState(false);

  // Get annotation queues for the appropriate scope
  const scope = type === "threads" ? AnnotationQueueScope.THREAD : AnnotationQueueScope.TRACE;
  
  const { data: queuesData, isPending } = useAnnotationQueuesList(
    {
      workspaceName,
      page,
      size,
      search: searchTerm,
      scope,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const addItemsMutation = useAnnotationQueueItemsAddMutation();

  const handleQueueSelect = useCallback(async (queue: AnnotationQueue) => {
    try {
      await addItemsMutation.mutateAsync({
        annotationQueueId: queue.id,
        itemIds: rows.map((row) => row.id),
      });

      toast({
        title: `${rows.length > 1 ? type.charAt(0).toUpperCase() + type.slice(1) : type.charAt(0).toUpperCase() + type.slice(1, -1)} added to annotation queue`,
        description: (
          <div className="flex flex-col gap-1">
            <div>
              <a 
                href="#" 
                className="text-blue-600 hover:text-blue-800 underline"
                onClick={(e) => {
                  e.preventDefault();
                  // TODO: Navigate to workspace invite page
                }}
              >
                Invite annotators to your workspace
              </a>
              {" "}and share this queue with them so they can start annotating and provide feedback to improve the evaluation of your LLM application.
            </div>
            <div>
              <a 
                href="#" 
                className="text-blue-600 hover:text-blue-800 underline text-sm"
                onClick={(e) => {
                  e.preventDefault();
                  navigator.clipboard.writeText(window.location.href);
                }}
              >
                Copy sharing link
              </a>
            </div>
          </div>
        ),
        className: "w-[468px] min-h-[120px] max-w-none flex flex-col items-start gap-1 self-stretch p-4 rounded-md border border-slate-200 bg-white shadow-lg",
        style: {
          boxShadow: "0 4px 6px -1px rgba(0, 0, 0, 0.10), 0 2px 4px -2px rgba(0, 0, 0, 0.10)",
          right: "max(16px, calc(100vw - 468px - 16px))"
        }
      });

      setOpen(false);
      setSearchTerm("");
      setPage(1);
    } catch (error) {
      // Error handling is done by the mutation hook
    }
  }, [addItemsMutation, rows, toast, setOpen, type]);

  const handleClose = useCallback(() => {
    setOpen(false);
    setSearchTerm("");
    setPage(1);
  }, [setOpen]);

  const handleQueueCreated = useCallback((newQueue: AnnotationQueue) => {
    // Close the create dialog
    setShowCreateDialog(false);
    // Immediately add to the newly created queue
    handleQueueSelect(newQueue);
  }, [handleQueueSelect]);

  const queues = queuesData?.content ?? [];
  const total = queuesData?.total ?? 0;

  const renderListItems = () => {
    if (isPending) {
      return <Loader />;
    }

    if (queues.length === 0) {
      const text = searchTerm ? "No search results" : "There are no annotation queues in this project yet";

      return (
        <div className="comet-body-s flex h-32 items-center justify-center text-muted-slate">
          {text}
        </div>
      );
    }

    // Since we're showing 1 queue at a time, just return the first (and only) queue
    const queue = queues[0];
    if (!queue) return null;

    return (
      <div
        className="rounded-lg p-6 cursor-pointer hover:bg-muted transition-colors"
        onClick={() => handleQueueSelect(queue)}
      >
        <div className="flex flex-col gap-2">
          <div className="flex items-center gap-2">
            <Users className="size-4 shrink-0 text-muted-slate" />
            <span className="comet-body-s-accented truncate w-full">
              {queue.name}
            </span>
          </div>
          {queue.description && (
            <div className="comet-body-s text-light-slate">
              {queue.description}
            </div>
          )}
        </div>
      </div>
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-2xl pb-8">
        <DialogHeader>
          <DialogTitle>Add to annotation queue</DialogTitle>
        </DialogHeader>
        <div className="w-full overflow-hidden">
          <ExplainerDescription
            className="mb-4"
            title=""
            description="Add traces to an annotation queue to collect human feedback on your LLM outputs. Only queues created in this project appear here, and traces can be added to them only."
          />
          <div className="flex gap-2.5">
            <SearchInput
              searchText={searchTerm}
              setSearchText={setSearchTerm}
              placeholder="Search annotation queues"
            />
            <Button
              variant="secondary"
              onClick={() => setShowCreateDialog(true)}
            >
              Create annotation queue
            </Button>
          </div>
          <div className="my-4 flex min-h-32 max-w-full flex-col justify-center">
            {renderListItems()}
          </div>
          {total > 0 && (
            <div className="pt-4 border-t">
              <DataTablePagination
                page={page}
                pageChange={setPage}
                size={size}
                sizeChange={setSize}
                total={total}
                hideSizeSelector={true}
              />
            </div>
          )}
        </div>
      </DialogContent>

      {/* Create Queue Dialog */}
      <CreateAnnotationQueueDialog
        open={showCreateDialog}
        setOpen={setShowCreateDialog}
        onSuccess={handleQueueCreated}
        defaultScope={scope}
      />
    </Dialog>
  );
};

export default AddToQueueDialog;
