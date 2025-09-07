import React, { useCallback, useState } from "react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Checkbox } from "@/components/ui/checkbox";
import { useToast } from "@/components/ui/use-toast";
import { SearchInput } from "@/components/shared/SearchInput/SearchInput";

import { Span, Trace, Thread } from "@/types/traces";
import { AnnotationQueue, AnnotationQueueScope } from "@/types/annotation-queues";
import useAppStore from "@/store/AppStore";
import useAnnotationQueuesList from "@/api/annotation-queues/useAnnotationQueuesList";
import { useAnnotationQueueItemsAddMutation } from "@/api/annotation-queues/useAnnotationQueueItemsMutation";
import CreateAnnotationQueueDialog from "@/components/pages/AnnotationQueuesPage/CreateAnnotationQueueDialog";

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
  const [selectedQueueIds, setSelectedQueueIds] = useState<Set<string>>(new Set());
  const [searchTerm, setSearchTerm] = useState("");
  const [currentPage, setCurrentPage] = useState(1);
  const [showCreateDialog, setShowCreateDialog] = useState(false);

  // Get annotation queues for the appropriate scope
  const scope = type === "threads" ? AnnotationQueueScope.THREAD : AnnotationQueueScope.TRACE;
  
  const { data: queuesData } = useAnnotationQueuesList({
    workspaceName,
    page: currentPage,
    size: 5, // Show 5 per page as per wireframe
    search: searchTerm,
    scope,
  });

  const addItemsMutation = useAnnotationQueueItemsAddMutation();

  const handleQueueToggle = useCallback((queueId: string) => {
    setSelectedQueueIds(prev => {
      const newSet = new Set(prev);
      if (newSet.has(queueId)) {
        newSet.delete(queueId);
      } else {
        newSet.add(queueId);
      }
      return newSet;
    });
  }, []);

  const handleAddToQueue = useCallback(async () => {
    if (selectedQueueIds.size === 0 || rows.length === 0) {
      return;
    }

    try {
      // Add to each selected queue
      for (const queueId of selectedQueueIds) {
        await addItemsMutation.mutateAsync({
          annotationQueueId: queueId,
          itemIds: rows.map((row) => row.id),
        });
      }

      toast({
        title: "Success",
        description: `Added ${rows.length} ${type} to ${selectedQueueIds.size} annotation queue${selectedQueueIds.size > 1 ? 's' : ''}`,
      });

      setOpen(false);
      setSelectedQueueIds(new Set());
      setSearchTerm("");
      setCurrentPage(1);
    } catch (error) {
      // Error handling is done by the mutation hook
    }
  }, [selectedQueueIds, rows, addItemsMutation, toast, setOpen, type]);

  const handleClose = useCallback(() => {
    setOpen(false);
    setSelectedQueueIds(new Set());
    setSearchTerm("");
    setCurrentPage(1);
  }, [setOpen]);

  const handleQueueCreated = useCallback((newQueue: AnnotationQueue) => {
    // Close the create dialog
    setShowCreateDialog(false);
    // Auto-select the newly created queue
    setSelectedQueueIds(new Set([newQueue.id]));
    // Show success message
    toast({
      title: "Success",
      description: `Created annotation queue "${newQueue.name}"`,
    });
  }, [toast]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-2xl max-h-[600px]">
        <DialogHeader>
          <DialogTitle>Add to Queue</DialogTitle>
        </DialogHeader>
        
        <div className="space-y-4">
          <div>
            <p className="text-sm text-muted-foreground mb-4">
              Add {rows.length} selected {type} to an annotation queue for review.
            </p>
            
            {/* Search Input with New Button */}
            <div className="flex items-center space-x-2">
              <div className="flex-1">
                <SearchInput
                  placeholder="Search queues..."
                  searchText={searchTerm}
                  setSearchText={setSearchTerm}
                />
              </div>
              <Button
                variant="outline"
                size="sm"
                onClick={() => setShowCreateDialog(true)}
              >
                New
              </Button>
            </div>
          </div>

          {/* Queue List */}
          <div className="border rounded-md">
            <div className="max-h-[300px] overflow-y-auto">
              <div className="p-4 space-y-3">
                {queuesData?.content?.map((queue: AnnotationQueue) => (
                  <div
                    key={queue.id}
                    className="flex items-center space-x-3 p-3 rounded-md border hover:bg-muted/50 cursor-pointer"
                    onClick={() => handleQueueToggle(queue.id)}
                  >
                    <Checkbox
                      checked={selectedQueueIds.has(queue.id)}
                      onChange={() => handleQueueToggle(queue.id)}
                    />
                    <div className="flex-1">
                      <div className="font-medium">{queue.name}</div>
                      {queue.description && (
                        <div className="text-sm text-muted-foreground">
                          {queue.description}
                        </div>
                      )}
                    </div>
                  </div>
                ))}
                
                {queuesData?.content?.length === 0 && (
                  <div className="text-center py-8 text-muted-foreground">
                    No queues found
                  </div>
                )}
              </div>
            </div>
          </div>

          {/* Pagination */}
          {queuesData && queuesData.total > 5 && (
            <div className="flex items-center justify-between">
              <div className="text-sm text-muted-foreground">
                Showing {(currentPage - 1) * 5 + 1}-{Math.min(currentPage * 5, queuesData.total)} of {queuesData.total} queues
              </div>
              <div className="flex items-center space-x-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setCurrentPage(prev => Math.max(1, prev - 1))}
                  disabled={currentPage === 1}
                >
                  Previous
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setCurrentPage(prev => prev + 1)}
                  disabled={currentPage * 5 >= queuesData.total}
                >
                  Next
                </Button>
              </div>
            </div>
          )}
        </div>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline" onClick={handleClose}>
              Cancel
            </Button>
          </DialogClose>
          <Button
            onClick={handleAddToQueue}
            disabled={selectedQueueIds.size === 0 || rows.length === 0 || addItemsMutation.isPending}
          >
            {addItemsMutation.isPending ? "Adding..." : `Add ${rows.length} ${type}`}
          </Button>
        </DialogFooter>
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
