import React, { useState } from "react";
import { useTranslation } from "react-i18next";
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
  const { t } = useTranslation();
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
          title: t("traces.addTagDialog.successTitle"),
          description: type === TRACE_DATA_TYPE.traces 
            ? t("traces.addTagDialog.successDescriptionTraces", { tag: newTag, count: rows.length })
            : t("traces.addTagDialog.successDescriptionSpans", { tag: newTag, count: rows.length }),
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
            {type === TRACE_DATA_TYPE.traces 
              ? t("traces.addTagDialog.titleTraces", { count: rows.length })
              : t("traces.addTagDialog.titleSpans", { count: rows.length })}
          </DialogTitle>
        </DialogHeader>
        {rows.length > MAX_ENTITIES && (
          <div className="mb-2 text-sm text-destructive">
            {t("traces.addTagDialog.maxEntitiesWarning", { max: MAX_ENTITIES })}
          </div>
        )}
        <div className="grid gap-4 py-4">
          <div className="flex items-center gap-4">
            <Input
              placeholder={t("traces.addTagDialog.newTagPlaceholder")}
              value={newTag}
              onChange={(event) => setNewTag(event.target.value)}
              className="col-span-3"
              disabled={rows.length > MAX_ENTITIES}
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={handleClose}>
            {t("common.cancel")}
          </Button>
          <Button
            onClick={handleAddTag}
            disabled={!newTag || rows.length > MAX_ENTITIES}
          >
            {t("traces.addTagDialog.addTag")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddTagDialog;
