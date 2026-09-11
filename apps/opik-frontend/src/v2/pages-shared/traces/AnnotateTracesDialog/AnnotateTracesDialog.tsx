import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Button } from "@/ui/button";
import { useToast } from "@/ui/use-toast";
import { Loader2 } from "lucide-react";
import { Span, Trace } from "@/types/traces";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import useTraceFeedbackScoreSetMutation from "@/api/traces/useTraceFeedbackScoreSetMutation";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useAppStore from "@/store/AppStore";
import { useQueryClient } from "@tanstack/react-query";
import {
  FEEDBACK_DEFINITION_TYPE,
  FeedbackDefinition,
} from "@/types/feedback-definitions";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { SelectItem } from "@/ui/select";
import { DropdownOption } from "@/types/shared";
import { Textarea } from "@/ui/textarea";
import FeedbackScoreValueInput from "@/v2/pages-shared/traces/FeedbackScoreValueInput/FeedbackScoreValueInput";
import { isNumericFeedbackScoreValid } from "@/lib/traces";

const MAX_ANNOTATE_ROWS = 500;
const MAX_CONCURRENT_ANNOTATIONS = 5;

type AnnotateTracesDialogProps = {
  rows: Array<Trace | Span>;
  open: boolean | number;
  setOpen: (open: boolean | number) => void;
  type: TRACE_DATA_TYPE;
};

type DraftScore = {
  name?: string;
  value?: number;
  categoryName?: string;
  reason?: string;
};

const AnnotateTracesDialog: React.FunctionComponent<
  AnnotateTracesDialogProps
> = ({ rows, open, setOpen, type }) => {
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const {
    data: feedbackDefinitionsData,
    isLoading: isDefinitionsLoading,
    isError: isDefinitionsError,
    refetch: refetchDefinitions,
  } = useFeedbackDefinitionsList(
    {
      workspaceName,
      page: 1,
      size: 1000,
    },
    { enabled: Boolean(open) },
  );
  const { mutateAsync: setFeedbackScore } = useTraceFeedbackScoreSetMutation();

  const [draft, setDraft] = useState<DraftScore>({});
  const [isSubmitting, setIsSubmitting] = useState(false);

  const isSpanType = type === TRACE_DATA_TYPE.spans;
  const entityCopy = isSpanType ? "spans" : "traces";

  const feedbackDefinitions: FeedbackDefinition[] = useMemo(
    () => feedbackDefinitionsData?.content || [],
    [feedbackDefinitionsData?.content],
  );

  const selectedDefinition = useMemo(
    () =>
      feedbackDefinitions.find((definition) => definition.name === draft.name),
    [feedbackDefinitions, draft.name],
  );

  const isValueValid = useMemo(() => {
    if (
      !selectedDefinition ||
      draft.value === undefined ||
      draft.value === null
    ) {
      return false;
    }
    if (selectedDefinition.type === FEEDBACK_DEFINITION_TYPE.numerical) {
      return isNumericFeedbackScoreValid(
        selectedDefinition.details as { min: number; max: number },
        draft.value,
      );
    }
    if (selectedDefinition.type === FEEDBACK_DEFINITION_TYPE.categorical) {
      const categories = selectedDefinition.details?.categories || {};
      return (
        draft.categoryName !== undefined &&
        draft.categoryName !== null &&
        Object.prototype.hasOwnProperty.call(categories, draft.categoryName) &&
        categories[draft.categoryName] !== null &&
        categories[draft.categoryName] !== undefined &&
        categories[draft.categoryName] === draft.value
      );
    }
    if (selectedDefinition.type === FEEDBACK_DEFINITION_TYPE.boolean) {
      return (
        (draft.categoryName === selectedDefinition.details.true_label &&
          draft.value === 1) ||
        (draft.categoryName === selectedDefinition.details.false_label &&
          draft.value === 0)
      );
    }
    return false;
  }, [selectedDefinition, draft.value, draft.categoryName]);

  useEffect(() => {
    if (open && rows.length > MAX_ANNOTATE_ROWS) {
      toast({
        title: "Error",
        description: `You can only annotate up to ${MAX_ANNOTATE_ROWS} ${entityCopy} at a time. Please select fewer items.`,
        variant: "destructive",
      });
      setOpen(false);
    }
  }, [open, rows.length, entityCopy, setOpen, toast]);

  const handleScoreNameChange = useCallback((name: string) => {
    setDraft((d) => ({
      name,
      value: undefined,
      categoryName: undefined,
      reason: d.reason,
    }));
  }, []);

  const handleApply = useCallback(async () => {
    if (!isValueValid || !draft.name || draft.value === undefined) {
      return;
    }

    setIsSubmitting(true);

    const results: Array<PromiseSettledResult<void>> = [];
    const submitOne = async (row: Trace | Span) => {
      try {
        await setFeedbackScore({
          name: draft.name!,
          value: draft.value!,
          categoryName: draft.categoryName,
          reason: draft.reason || undefined,
          traceId: isSpanType ? (row as Span).trace_id : (row as Trace).id,
          spanId: isSpanType ? (row as Span).id : undefined,
          silent: true,
        });
        results.push({ status: "fulfilled", value: undefined });
      } catch (error) {
        results.push({ status: "rejected", reason: error });
      }
    };

    let cursor = 0;
    const workerCount = Math.min(MAX_CONCURRENT_ANNOTATIONS, rows.length);
    await Promise.all(
      Array.from({ length: workerCount }, async () => {
        while (cursor < rows.length) {
          const index = cursor++;
          if (index >= rows.length) {
            break;
          }
          const row = rows[index];
          await submitOne(row);
        }
      }),
    );

    const failed = results.filter(
      (result) => result.status === "rejected",
    ).length;

    if (results.some((result) => result.status === "fulfilled")) {
      await queryClient.invalidateQueries({
        queryKey: [isSpanType ? "spans" : "traces"],
      });
    }

    setIsSubmitting(false);

    if (failed === 0) {
      toast({
        title: "Annotations applied",
        description: `Successfully annotated ${rows.length} ${entityCopy}.`,
      });
      setDraft({});
      setOpen(false);
    } else if (failed === rows.length) {
      toast({
        title: "Error",
        description: `Failed to annotate all selected ${entityCopy}.`,
        variant: "destructive",
      });
    } else {
      toast({
        title: "Partially applied",
        description: `${rows.length - failed} of ${
          rows.length
        } ${entityCopy} annotated successfully.`,
        variant: "destructive",
      });
      setDraft({});
      setOpen(false);
    }
  }, [
    isValueValid,
    draft,
    rows,
    isSpanType,
    entityCopy,
    setFeedbackScore,
    queryClient,
    setOpen,
    toast,
  ]);

  return (
    <Dialog open={Boolean(open)} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg" data-testid="annotate-bulk-dialog">
        <DialogHeader>
          <DialogTitle>Annotate {entityCopy}</DialogTitle>
        </DialogHeader>
        <p className="comet-body-s text-light-slate">
          Apply the same feedback score to {rows.length} selected {entityCopy}.
        </p>
        <div className="flex flex-col gap-3 py-2">
          {isDefinitionsError ? (
            <div
              className="comet-body-s text-red-600"
              data-testid="annotate-bulk-error"
            >
              Failed to load feedback definitions.
              <Button
                variant="link"
                size="sm"
                onClick={() => refetchDefinitions()}
              >
                Retry
              </Button>
            </div>
          ) : isDefinitionsLoading ? (
            <div className="comet-body-s text-light-slate">
              Loading feedback definitions…
            </div>
          ) : (
            <>
              <div>
                <div className="comet-body-s-accented pb-1">Score</div>
                <SelectBox
                  value={draft.name ?? ""}
                  options={feedbackDefinitions.map((definition) => ({
                    label: definition.name,
                    value: definition.name,
                  }))}
                  onChange={handleScoreNameChange}
                  className="h-8 min-w-[200px] py-1"
                  testId="annotate-bulk-score-select"
                  disabled={isSubmitting}
                  renderTrigger={(value) => {
                    if (!value) {
                      return <div className="truncate">Select a score</div>;
                    }
                    return <span className="text-nowrap">{value}</span>;
                  }}
                  renderOption={(option: DropdownOption<string>) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  )}
                />
              </div>
              {selectedDefinition && (
                <div>
                  <div className="comet-body-s-accented pb-1">Value</div>
                  <FeedbackScoreValueInput
                    feedbackDefinition={selectedDefinition}
                    value={draft.value ?? ""}
                    categoryName={draft.categoryName}
                    disabled={isSubmitting}
                    onChange={({
                      value: newValue,
                      categoryName: newCategory,
                    }) =>
                      setDraft((d) => ({
                        ...d,
                        value: newValue,
                        categoryName: newCategory,
                      }))
                    }
                    testIdPrefix="annotate-bulk"
                  />
                </div>
              )}
              {selectedDefinition && (
                <div>
                  <div className="comet-body-s-accented pb-1">
                    Reason (optional)
                  </div>
                  <Textarea
                    placeholder="Add a reason..."
                    value={draft.reason ?? ""}
                    onChange={(event) =>
                      setDraft((d) => ({ ...d, reason: event.target.value }))
                    }
                    className="min-h-8 resize-none py-1"
                    data-testid="annotate-bulk-reason-input"
                    disabled={isSubmitting}
                  />
                </div>
              )}
            </>
          )}
        </div>
        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => setOpen(false)}
            disabled={isSubmitting}
          >
            Cancel
          </Button>
          <Button
            onClick={handleApply}
            disabled={!isValueValid || isSubmitting}
            data-testid="annotate-bulk-apply-button"
          >
            {isSubmitting && <Loader2 className="mr-1 size-3 animate-spin" />}
            Apply to {rows.length} {entityCopy}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AnnotateTracesDialog;
