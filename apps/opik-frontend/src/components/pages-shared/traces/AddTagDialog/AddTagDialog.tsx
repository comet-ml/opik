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

  const handleClose = () => {
    setOpen(false);
    setNewTag("");
  };

  const handleAddTag = () => {
    if (!newTag) return;

    rows.forEach((row) => {
      const currentTags = row.tags || [];

      if (currentTags.includes(newTag)) return;

      const newTags = [...currentTags, newTag];

      if (type === TRACE_DATA_TYPE.traces) {
        traceUpdateMutation.mutate({
          projectId,
          traceId: row.id,
          trace: {
            workspace_name: workspaceName,
            project_id: projectId,
            tags: newTags,
          },
        });
      } else {
        const span = row as Span;
        const parentId = span.parent_span_id;

        spanUpdateMutation.mutate({
          projectId,
          spanId: span.id,
          span: {
            workspace_name: workspaceName,
            project_id: projectId,
            ...(parentId && { parent_span_id: parentId }),
            trace_id: span.trace_id,
            tags: newTags,
          },
        });
      }
    });

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
