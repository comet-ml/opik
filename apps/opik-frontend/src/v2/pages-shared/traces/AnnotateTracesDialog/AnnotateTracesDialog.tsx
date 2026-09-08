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
import {
  FEEDBACK_DEFINITION_TYPE,
  FeedbackDefinition,
} from "@/types/feedback-definitions";
import { isNumericFeedbackScoreValid } from "@/lib/traces";
import DebounceInput from "@/shared/DebounceInput/DebounceInput";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { SelectItem } from "@/ui/select";
import { DropdownOption } from "@/types/shared";
import { Textarea } from "@/ui/textarea";

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
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data: feedbackDefinitionsData } = useFeedbackDefinitionsList({
    workspaceName,
    page: 1,
    size: 1000,
  });
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
    if (!selectedDefinition || draft.value === undefined) {
      return false;
    }
    if (selectedDefinition.type === FEEDBACK_DEFINITION_TYPE.numerical) {
      return isNumericFeedbackScoreValid(
        selectedDefinition.details as { min: number; max: number },
        draft.value,
      );
    }
    return true;
  }, [selectedDefinition, draft.value]);

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

  const handleNumericValueChange = useCallback(
    (value: string | number | readonly string[] | undefined) => {
      const num =
        typeof value === "string" && value !== "" ? Number(value) : value;
      if (
        typeof num !== "number" ||
        Number.isNaN(num) ||
        !selectedDefinition ||
        !isNumericFeedbackScoreValid(
          selectedDefinition.details as { min: number; max: number },
          num,
        )
      ) {
        setDraft((d) => ({ ...d, value: undefined }));
        return;
      }
      setDraft((d) => ({ ...d, value: num }));
    },
    [selectedDefinition],
  );

  const handleApply = useCallback(async () => {
    if (!isValueValid || !draft.name || draft.value === undefined) {
      return;
    }

    setIsSubmitting(true);

    const results: Array<PromiseSettledResult<void>> = [];
    const queue = [...rows];
    const submitOne = async (row: Trace | Span) => {
      try {
        await setFeedbackScore({
          name: draft.name!,
          value: draft.value!,
          categoryName: draft.categoryName,
          reason: draft.reason || undefined,
          traceId: isSpanType ? (row as Span).trace_id : (row as Trace).id,
          spanId: isSpanType ? (row as Span).id : undefined,
        });
        results.push({ status: "fulfilled", value: undefined });
      } catch (error) {
        results.push({ status: "rejected", reason: error });
      }
    };
    await Promise.all(
      Array.from(
        { length: Math.min(MAX_CONCURRENT_ANNOTATIONS, queue.length) },
        async () => {
          while (queue.length > 0) {
            const row = queue.shift();
            if (!row) {
              break;
            }
            await submitOne(row);
          }
        },
      ),
    );

    const failed = results.filter(
      (result) => result.status === "rejected",
    ).length;

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
    setOpen,
    toast,
  ]);

  const renderValueInput = () => {
    if (!selectedDefinition) {
      return null;
    }

    if (selectedDefinition.type === FEEDBACK_DEFINITION_TYPE.numerical) {
      return (
        <DebounceInput
          className="my-0.5 h-8 min-w-[120px] py-1"
          max={selectedDefinition.details.max}
          min={selectedDefinition.details.min}
          step="any"
          dimension="sm"
          delay={300}
          onValueChange={handleNumericValueChange}
          placeholder="Score"
          type="number"
          value={draft.value ?? ""}
          data-testid="annotate-bulk-score-input"
        />
      );
    }

    if (selectedDefinition.type === FEEDBACK_DEFINITION_TYPE.boolean) {
      return (
        <ToggleGroup
          className="min-w-fit p-0.5"
          onValueChange={(value?: string) => {
            if (!value) {
              return;
            }
            setDraft((d) => ({
              ...d,
              value: value === selectedDefinition.details.true_label ? 1 : 0,
              categoryName: value,
            }));
          }}
          variant="outline"
          type="single"
          size="md"
          value={draft.categoryName ?? ""}
        >
          <ToggleGroupItem
            className="w-full"
            key="true"
            value={selectedDefinition.details.true_label}
          >
            {selectedDefinition.details.true_label}
          </ToggleGroupItem>
          <ToggleGroupItem
            className="w-full"
            key="false"
            value={selectedDefinition.details.false_label}
          >
            {selectedDefinition.details.false_label}
          </ToggleGroupItem>
        </ToggleGroup>
      );
    }

    if (selectedDefinition.type === FEEDBACK_DEFINITION_TYPE.categorical) {
      const categoricalOptionList = Object.entries(
        selectedDefinition.details.categories,
      ).map(([name, value]) => ({
        label: name,
        value: name,
        description: String(value),
      }));

      return (
        <SelectBox
          value={draft.categoryName ?? ""}
          options={categoricalOptionList}
          onChange={(value?: string) => {
            if (!value) {
              return;
            }
            const categoryValue = Object.entries(
              selectedDefinition.details.categories,
            ).find(([categoryName]) => categoryName === value)?.[1];

            setDraft((d) => ({
              ...d,
              categoryName: value,
              value: categoryValue,
            }));
          }}
          className="my-0.5 h-8 min-w-[160px] py-1"
          testId="annotate-bulk-category-select"
          renderTrigger={(value) => {
            if (!value) {
              return <div className="truncate">Select a category</div>;
            }
            return <span className="text-nowrap">{value}</span>;
          }}
          renderOption={(option: DropdownOption<string>) => (
            <SelectItem key={option.value} value={option.value}>
              {option.value} ({option.description})
            </SelectItem>
          )}
        />
      );
    }

    return null;
  };

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
              {renderValueInput()}
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
              />
            </div>
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
