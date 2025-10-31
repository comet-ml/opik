import React, { useState } from "react";
import { Trace, Span } from "@/types/traces";
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
import useTraceUpdateMutation from "@/api/traces/useTraceUpdateMutation";
import useSpanUpdateMutation from "@/api/traces/useSpanUpdateMutation";
import useAppStore from "@/store/AppStore";

type AddTagDialogProps = {
  rows: Array<Trace | Span>;
  open: boolean | number;
  setOpen: (open: boolean | number) => void;
  projectId: string;
  type: TRACE_DATA_TYPE;
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
  const traceUpdateMutation = useTraceUpdateMutation();
  const spanUpdateMutation = useSpanUpdateMutation();
  const MAX_ENTITIES = 10;

  const handleClose = () => {
    setOpen(false);
    setNewTag("");
  };

  const handleAddTag = () => {
    if (!newTag) return;

    const promises: Promise<unknown>[] = [];

    rows.forEach((row) => {
      const currentTags = row.tags || [];

      if (currentTags.includes(newTag)) return;

      const newTags = [...currentTags, newTag];

      if (type === TRACE_DATA_TYPE.traces) {
        promises.push(
          traceUpdateMutation.mutateAsync({
            projectId,
            traceId: row.id,
            trace: {
              workspace_name: workspaceName,
              project_id: projectId,
              tags: newTags,
            },
          }),
        );
      } else {
        const span = row as Span;
        const parentId = span.parent_span_id;

        promises.push(
          spanUpdateMutation.mutateAsync({
            projectId,
            spanId: span.id,
            span: {
              workspace_name: workspaceName,
              project_id: projectId,
              ...(parentId && { parent_span_id: parentId }),
              trace_id: span.trace_id,
              tags: newTags,
            },
          }),
        );
      }
    });

    Promise.all(promises)
      .then(() => {
        toast({
          title: "Success",
          description: `Tag "${newTag}" added to ${rows.length} selected ${
            type === TRACE_DATA_TYPE.traces ? "traces" : "spans"
          }`,
        });

        if (onSuccess) {
          onSuccess();
        }

        handleClose();
      })
      .catch(() => {
        // Error handling is already done by the mutation hooks,this just ensures we don't close the dialog on error
      });
  };

  return (
    <Dialog open={Boolean(open)} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-[425px]">
        <DialogHeader>
          <DialogTitle>
            Add tag to {rows.length}{" "}
            {type === TRACE_DATA_TYPE.traces ? "traces" : "spans"}
          </DialogTitle>
        </DialogHeader>
        {rows.length > MAX_ENTITIES && (
          <div className="mb-2 text-sm text-destructive">
            You can only add tags to up to {MAX_ENTITIES} entities at a time.
            Please select fewer entities.
          </div>
        )}
        <div className="grid gap-4 py-4">
          <div className="flex items-center gap-4">
            <Input
              placeholder="New tag"
              value={newTag}
              onChange={(event) => setNewTag(event.target.value)}
              className="col-span-3"
              disabled={rows.length > MAX_ENTITIES}
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button
            onClick={handleAddTag}
            disabled={!newTag || rows.length > MAX_ENTITIES}
          >
            Add tag
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddTagDialog;
