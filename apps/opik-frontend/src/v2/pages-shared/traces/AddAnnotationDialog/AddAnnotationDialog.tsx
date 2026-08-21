import React, { useCallback, useMemo, useState } from "react";
import {
  FEEDBACK_SCORE_TYPE,
  Span,
  Trace,
  TraceFeedbackScore,
} from "@/types/traces";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Button } from "@/ui/button";
import { useToast } from "@/ui/use-toast";
import FeedbackScoresEditor from "@/v2/pages-shared/traces/FeedbackScoresEditor/FeedbackScoresEditor";
import { UpdateFeedbackScoreData } from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceAnnotateViewer/types";
import useTraceFeedbackScoreBatchSetMutation from "@/api/traces/useTraceFeedbackScoreBatchSetMutation";
import useSpanFeedbackScoreBatchSetMutation from "@/api/traces/useSpanFeedbackScoreBatchSetMutation";

const ENTITY_COPY: Record<TRACE_DATA_TYPE, { one: string; many: string }> = {
  [TRACE_DATA_TYPE.traces]: { one: "trace", many: "traces" },
  [TRACE_DATA_TYPE.spans]: { one: "span", many: "spans" },
};

type AddAnnotationDialogProps = {
  rows: Array<Trace | Span>;
  open: boolean | number;
  setOpen: (open: boolean | number) => void;
  projectName: string;
  type: TRACE_DATA_TYPE;
};

const AddAnnotationDialog: React.FunctionComponent<
  AddAnnotationDialogProps
> = ({ rows, open, setOpen, projectName, type }) => {
  const { toast } = useToast();
  const [pendingScores, setPendingScores] = useState<
    Record<string, TraceFeedbackScore>
  >({});

  const { mutateAsync: setTraceFeedbackScores, isPending: isTracePending } =
    useTraceFeedbackScoreBatchSetMutation();
  const { mutateAsync: setSpanFeedbackScores, isPending: isSpanPending } =
    useSpanFeedbackScoreBatchSetMutation();

  const isPending = isTracePending || isSpanPending;

  const handleClose = useCallback(() => {
    setOpen(false);
    setPendingScores({});
  }, [setOpen]);

  const onUpdateFeedbackScore = useCallback(
    (update: UpdateFeedbackScoreData) => {
      setPendingScores((scores) => ({
        ...scores,
        [update.name]: {
          name: update.name,
          value: update.value,
          category_name: update.categoryName,
          reason: update.reason,
          source: FEEDBACK_SCORE_TYPE.ui,
        },
      }));
    },
    [],
  );

  const onDeleteFeedbackScore = useCallback((name: string) => {
    setPendingScores((scores) => {
      const nextScores = { ...scores };
      delete nextScores[name];
      return nextScores;
    });
  }, []);

  const feedbackScores = useMemo(
    () => Object.values(pendingScores),
    [pendingScores],
  );

  const entityCopy = ENTITY_COPY[type];
  const entityLabel = rows.length === 1 ? entityCopy.one : entityCopy.many;

  const handleApply = async () => {
    const scores = rows.flatMap((row) =>
      feedbackScores.map((score) => ({
        id: row.id,
        name: score.name,
        value: score.value,
        categoryName: score.category_name,
        reason: score.reason,
      })),
    );

    try {
      if (type === TRACE_DATA_TYPE.traces) {
        await setTraceFeedbackScores({ projectName, scores });
      } else {
        await setSpanFeedbackScores({ projectName, scores });
      }

      toast({
        title: "Feedback scores applied",
        description: `${feedbackScores.length} ${
          feedbackScores.length === 1 ? "score" : "scores"
        } applied to ${rows.length} ${entityLabel}`,
      });
      handleClose();
    } catch {
      // Error handling is done by the mutation hook
    }
  };

  return (
    <Dialog
      open={Boolean(open)}
      onOpenChange={(nextOpen) => !nextOpen && handleClose()}
    >
      <DialogContent className="outline-none sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>
            Annotate {rows.length} {entityLabel}
          </DialogTitle>
          <DialogDescription className="mt-2">
            Set feedback scores to apply to all selected {entityLabel}.
          </DialogDescription>
        </DialogHeader>

        <div className="max-h-80 overflow-y-auto py-2">
          <FeedbackScoresEditor
            feedbackScores={feedbackScores}
            onUpdateFeedbackScore={onUpdateFeedbackScore}
            onDeleteFeedbackScore={onDeleteFeedbackScore}
            header={<FeedbackScoresEditor.Header title="Feedback scores" />}
            footer={
              <FeedbackScoresEditor.Footer entityCopy={entityCopy.many} />
            }
          />
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button
            onClick={handleApply}
            disabled={feedbackScores.length === 0 || isPending}
            data-testid="apply-annotation-button"
          >
            Apply to {rows.length} {entityLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddAnnotationDialog;
