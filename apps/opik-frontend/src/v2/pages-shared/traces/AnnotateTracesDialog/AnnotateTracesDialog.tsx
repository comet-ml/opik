import React, { useMemo, useState } from "react";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useTraceFeedbackScoreSetMutation from "@/api/traces/useTraceFeedbackScoreSetMutation";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { isNumericFeedbackScoreValid } from "@/lib/traces";
import useAppStore from "@/store/AppStore";
import { FEEDBACK_DEFINITION_TYPE } from "@/types/feedback-definitions";
import { Span, Trace } from "@/types/traces";
import { Button } from "@/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/ui/select";
import { Textarea } from "@/ui/textarea";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import { useToast } from "@/ui/use-toast";

const BOOLEAN_TRUE_ID = "__boolean_true__";
const BOOLEAN_FALSE_ID = "__boolean_false__";
const BULK_ANNOTATE_CONCURRENCY = 5;

type CategoryChoice = {
  id: string;
  label: string;
  value: number;
};

const mapWithConcurrency = async <T, R>(
  items: T[],
  concurrency: number,
  mapper: (item: T) => Promise<R>,
): Promise<PromiseSettledResult<R>[]> => {
  if (items.length === 0) {
    return [];
  }

  const results: PromiseSettledResult<R>[] = new Array(items.length);
  let nextIndex = 0;

  const worker = async () => {
    while (true) {
      const index = nextIndex++;
      if (index >= items.length) {
        return;
      }

      try {
        const value = await mapper(items[index]);
        results[index] = { status: "fulfilled", value };
      } catch (reason) {
        results[index] = { status: "rejected", reason };
      }
    }
  };

  await Promise.all(
    Array.from({ length: Math.min(concurrency, items.length) }, () => worker()),
  );

  return results;
};

type AnnotateTracesDialogProps = {
  rows: Array<Trace | Span>;
  type: TRACE_DATA_TYPE;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const AnnotateTracesDialog: React.FC<AnnotateTracesDialogProps> = ({
  rows,
  type,
  open,
  setOpen,
}) => {
  const [definitionId, setDefinitionId] = useState("");
  const [value, setValue] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [reason, setReason] = useState("");
  const [isApplying, setIsApplying] = useState(false);
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data, isPending, isError } = useFeedbackDefinitionsList(
    { workspaceName, page: 1, size: 10000 },
    { enabled: open },
  );
  const { mutateAsync } = useTraceFeedbackScoreSetMutation();
  const { toast } = useToast();
  const definition = useMemo(
    () => data?.content.find((item) => item.id === definitionId),
    [data?.content, definitionId],
  );
  const categories = useMemo<CategoryChoice[]>(() => {
    if (definition?.type === FEEDBACK_DEFINITION_TYPE.categorical) {
      return Object.entries(definition.details.categories)
        .sort((a, b) => a[1] - b[1])
        .map(([name, categoryValue]) => ({
          id: name,
          label: name,
          value: categoryValue,
        }));
    }

    if (definition?.type === FEEDBACK_DEFINITION_TYPE.boolean) {
      return [
        {
          id: BOOLEAN_TRUE_ID,
          label: definition.details.true_label,
          value: 1,
        },
        {
          id: BOOLEAN_FALSE_ID,
          label: definition.details.false_label,
          value: 0,
        },
      ];
    }

    return [];
  }, [definition]);
  const selectedCategory = categories.find(
    (category) => category.id === categoryId,
  );
  const score =
    definition?.type === FEEDBACK_DEFINITION_TYPE.numerical
      ? value.trim() === ""
        ? undefined
        : Number(value)
      : selectedCategory?.value;
  const valid =
    !!definition &&
    score !== undefined &&
    Number.isFinite(score) &&
    (definition.type !== FEEDBACK_DEFINITION_TYPE.numerical ||
      isNumericFeedbackScoreValid(definition.details, score));

  const handleClose = () => {
    if (isApplying) return;
    setDefinitionId("");
    setValue("");
    setCategoryId("");
    setReason("");
    setOpen(false);
  };

  const handleApply = async () => {
    if (
      !valid ||
      score === undefined ||
      !definition ||
      !rows.length ||
      isApplying
    )
      return;
    setIsApplying(true);
    try {
      // Wait for every request, including after a partial failure.
      const results = await mapWithConcurrency(
        rows,
        BULK_ANNOTATE_CONCURRENCY,
        (row) =>
          mutateAsync({
            name: definition.name,
            value: score,
            reason: reason || undefined,
            categoryName:
              definition.type === FEEDBACK_DEFINITION_TYPE.numerical
                ? undefined
                : selectedCategory?.label,
            traceId:
              type === TRACE_DATA_TYPE.spans ? (row as Span).trace_id : row.id,
            ...(type === TRACE_DATA_TYPE.spans && { spanId: row.id }),
          }),
      );
      if (results.every((result) => result.status === "fulfilled")) {
        toast({ title: "Annotations applied" });
        setOpen(false);
      }
    } finally {
      // The mutation hook displays request errors.
      setIsApplying(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-[600px]">
        <DialogHeader>
          <DialogTitle>
            <span>
              {type === TRACE_DATA_TYPE.spans
                ? "Annotate spans"
                : "Annotate traces"}
            </span>
          </DialogTitle>
          <DialogDescription>
            <span>{rows.length} selected</span>
          </DialogDescription>
        </DialogHeader>
        <DialogAutoScrollBody>
          <fieldset disabled={isApplying} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="bulk-feedback-definition">
                Feedback definition
              </Label>
              <Select
                value={definitionId}
                onValueChange={(id) => {
                  setDefinitionId(id);
                  setValue("");
                  setCategoryId("");
                }}
                disabled={isApplying || isPending || isError}
              >
                <SelectTrigger
                  id="bulk-feedback-definition"
                  data-testid="annotate-bulk-score-select"
                >
                  <SelectValue placeholder="Select a feedback definition" />
                </SelectTrigger>
                <SelectContent>
                  {data?.content.map((item) => (
                    <SelectItem
                      key={item.id}
                      value={item.id}
                      data-testid={`annotate-bulk-score-select-option-${item.name}`}
                    >
                      <span>{item.name}</span>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {isError && (
                <p role="alert">Unable to load feedback definitions.</p>
              )}
              {!isPending && !isError && !data?.content.length && (
                <p>No feedback definitions available.</p>
              )}
            </div>
            {definition?.type === FEEDBACK_DEFINITION_TYPE.numerical && (
              <div className="space-y-2">
                <Label htmlFor="bulk-feedback-value">
                  <span>
                    Score ({definition.details.min}–{definition.details.max})
                  </span>
                </Label>
                <Input
                  id="bulk-feedback-value"
                  type="number"
                  step="any"
                  min={definition.details.min}
                  max={definition.details.max}
                  value={value}
                  onChange={(event) => setValue(event.target.value)}
                  data-testid="annotate-bulk-score-input"
                />
              </div>
            )}
            {categories.length > 0 && (
              <ToggleGroup
                type="single"
                variant="outline"
                value={categoryId}
                onValueChange={setCategoryId}
                disabled={isApplying}
                className="flex-wrap justify-start"
                aria-label="Score"
              >
                {categories.map((category) => (
                  <ToggleGroupItem
                    key={category.id}
                    value={category.id}
                    data-testid={`annotate-bulk-category-toggle-${category.id}`}
                  >
                    <span>
                      {definition?.type === FEEDBACK_DEFINITION_TYPE.boolean
                        ? category.label
                        : `${category.label} (${category.value})`}
                    </span>
                  </ToggleGroupItem>
                ))}
              </ToggleGroup>
            )}
            <div className="space-y-2">
              <Label htmlFor="bulk-feedback-reason">Reason (optional)</Label>
              <Textarea
                id="bulk-feedback-reason"
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                placeholder="Add a reason..."
                data-testid="annotate-bulk-reason-input"
              />
            </div>
          </fieldset>
        </DialogAutoScrollBody>
        <DialogFooter>
          <Button variant="outline" disabled={isApplying} onClick={handleClose}>
            Cancel
          </Button>
          <Button
            disabled={!valid || !rows.length || isApplying}
            onClick={handleApply}
            data-testid="annotate-bulk-apply-button"
          >
            <span>{isApplying ? "Applying..." : "Apply"}</span>
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AnnotateTracesDialog;
