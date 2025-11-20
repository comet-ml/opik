import React, { useState } from "react";
import { Trace, Span, Thread } from "@/types/traces";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useToast } from "@/components/ui/use-toast";
import useTraceBatchUpdateMutation from "@/api/traces/useTraceBatchUpdateMutation";
import useSpanBatchUpdateMutation from "@/api/traces/useSpanBatchUpdateMutation";
import useThreadBatchUpdateMutation from "@/api/traces/useThreadBatchUpdateMutation";
import useAppStore from "@/store/AppStore";

export enum TAG_ENTITY_TYPE {
  traces = "traces",
  spans = "spans",
  threads = "threads",
}

type AddTagDialogProps = {
  rows: Array<Trace | Span | Thread>;
  open: boolean | number;
  setOpen: (open: boolean | number) => void;
  projectId: string;
  type: TRACE_DATA_TYPE | TAG_ENTITY_TYPE.threads;
  onSuccess?: () => void;
};

const AddTagDialog: React.FunctionComponent<AddTagDialogProps> = ({
  rows,
  open,
  setOpen,
  projectId,
  type,
  onSuccess,
}) => {
  const { toast } = useToast();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [newTag, setNewTag] = useState<string>("");
  const traceBatchUpdateMutation = useTraceBatchUpdateMutation();
  const spanBatchUpdateMutation = useSpanBatchUpdateMutation();
  const threadBatchUpdateMutation = useThreadBatchUpdateMutation();

  const handleClose = () => {
    setOpen(false);
    setNewTag("");
  };

  const handleAddTag = () => {
    if (!newTag) return;

    let mutationPromise;
    let entityName;

    if (type === TRACE_DATA_TYPE.traces) {
      const ids = rows.map((row) => row.id);
      mutationPromise = traceBatchUpdateMutation.mutateAsync({
        projectId,
        traceIds: ids,
        trace: {
          workspace_name: workspaceName,
          project_id: projectId,
          tags: [newTag],
        },
        mergeTags: true,
      });
      entityName = "traces";
    } else if (type === TRACE_DATA_TYPE.spans) {
      const ids = rows.map((row) => row.id);
      mutationPromise = spanBatchUpdateMutation.mutateAsync({
        projectId,
        spanIds: ids,
        span: {
          workspace_name: workspaceName,
          project_id: projectId,
          trace_id: "00000000-0000-0000-0000-000000000000", // Placeholder - not used by backend batch update, just to bypass validation
          tags: [newTag],
        },
        mergeTags: true,
      });
      entityName = "spans";
    } else {
      // threads - use thread_model_id instead of id
      const threadModelIds = rows.map((row) => (row as Thread).thread_model_id);
      mutationPromise = threadBatchUpdateMutation.mutateAsync({
        projectId,
        threadIds: threadModelIds,
        thread: {
          tags: [newTag],
        },
        mergeTags: true,
      });
      entityName = "threads";
    }

    mutationPromise
      .then(() => {
        toast({
          title: "Success",
          description: `Tag "${newTag}" added to ${rows.length} selected ${entityName}`,
        });

        if (onSuccess) {
          onSuccess();
        }

        handleClose();
      })
      .catch(() => {
        // Error handling is already done by the mutation hooks
      });
  };

  return (
    <Dialog open={Boolean(open)} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-[425px]">
        <DialogHeader>
          <DialogTitle>
            Add tag to {rows.length}{" "}
            {type === TRACE_DATA_TYPE.traces
              ? "traces"
              : type === TRACE_DATA_TYPE.spans
                ? "spans"
                : "threads"}
          </DialogTitle>
        </DialogHeader>
        <div className="grid gap-4 py-4">
          <div className="flex items-center gap-4">
            <Input
              placeholder="New tag"
              value={newTag}
              onChange={(event) => setNewTag(event.target.value)}
              className="col-span-3"
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button onClick={handleAddTag} disabled={!newTag}>
            Add tag
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddTagDialog;
