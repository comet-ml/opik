import React, { useCallback } from "react";
import { Trace, Span, Thread } from "@/types/traces";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import useTraceBatchUpdateMutation from "@/api/traces/useTraceBatchUpdateMutation";
import useSpanBatchUpdateMutation from "@/api/traces/useSpanBatchUpdateMutation";
import useThreadBatchUpdateMutation from "@/api/traces/useThreadBatchUpdateMutation";
import useAppStore from "@/store/AppStore";
import ManageTagsDialog from "@/components/shared/ManageTagsDialog/ManageTagsDialog";

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
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const traceBatchUpdateMutation = useTraceBatchUpdateMutation();
  const spanBatchUpdateMutation = useSpanBatchUpdateMutation();
  const threadBatchUpdateMutation = useThreadBatchUpdateMutation();

  const handleUpdate = useCallback(
    async (tagsToAdd: string[], tagsToRemove: string[]) => {
      if (type === TRACE_DATA_TYPE.traces) {
        const ids = rows.map((row) => row.id);
        await traceBatchUpdateMutation.mutateAsync({
          projectId,
          traceIds: ids,
          trace: {
            workspace_name: workspaceName,
            project_id: projectId,
            tagsToAdd,
            tagsToRemove,
          },
        });
      } else if (type === TRACE_DATA_TYPE.spans) {
        const ids = rows.map((row) => row.id);
        await spanBatchUpdateMutation.mutateAsync({
          projectId,
          spanIds: ids,
          span: {
            workspace_name: workspaceName,
            project_id: projectId,
            trace_id: "00000000-0000-0000-0000-000000000000", // Placeholder - not used by backend batch update, just to bypass validation
            tagsToAdd,
            tagsToRemove,
          },
        });
      } else {
        const threadModelIds = rows.map(
          (row) => (row as Thread).thread_model_id,
        );
        await threadBatchUpdateMutation.mutateAsync({
          projectId,
          threadIds: threadModelIds,
          thread: {
            tagsToAdd,
            tagsToRemove,
          },
        });
      }

      if (onSuccess) {
        onSuccess();
      }
    },
    [
      type,
      rows,
      projectId,
      workspaceName,
      traceBatchUpdateMutation,
      spanBatchUpdateMutation,
      threadBatchUpdateMutation,
      onSuccess,
    ],
  );

  return (
    <ManageTagsDialog
      entities={rows}
      open={open}
      setOpen={setOpen}
      onUpdate={handleUpdate}
    />
  );
};

export default AddTagDialog;
