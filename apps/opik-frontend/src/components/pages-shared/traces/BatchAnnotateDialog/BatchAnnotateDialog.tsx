import React, { useCallback, useMemo, useState } from "react";
import { sortBy } from "lodash";
import isNumber from "lodash/isNumber";
import { X } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
  DialogDescription,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";
import { Trace, Span } from "@/types/traces";
import {
  FEEDBACK_DEFINITION_TYPE,
  FeedbackDefinition,
} from "@/types/feedback-definitions";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useAppStore from "@/store/AppStore";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import useTraceFeedbackScoreSetMutation from "@/api/traces/useTraceFeedbackScoreSetMutation";
import ColoredTagNew from "@/components/shared/ColoredTag/ColoredTagNew";
import DebounceInput from "@/components/shared/DebounceInput/DebounceInput";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { SelectItem } from "@/components/ui/select";
import { DropdownOption } from "@/types/shared";
import { isNumericFeedbackScoreValid } from "@/lib/traces";
import { categoryOptionLabelRenderer } from "@/lib/feedback-scores";

type ScoreEntry = {
  name: string;
  value: number;
  categoryName?: string;
};

type BatchAnnotateDialogProps = {
  rows: Array<Trace | Span>;
  open: boolean | number;
  setOpen: (open: boolean | number) => void;
  type: TRACE_DATA_TYPE;
};

const BatchAnnotateDialog: React.FunctionComponent<
  BatchAnnotateDialogProps
> = ({ rows, open, setOpen, type }) => {
  const { toast } = useToast();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [scores, setScores] = useState<Map<string, ScoreEntry>>(new Map());
  const [isSubmitting, setIsSubmitting] = useState(false);

  const { mutateAsync: setFeedbackScore } = useTraceFeedbackScoreSetMutation();

  const { data: feedbackDefinitionsData } = useFeedbackDefinitionsList({
    workspaceName,
    page: 1,
    size: 1000,
  });

  const feedbackDefinitions: FeedbackDefinition[] = useMemo(() => {
    return sortBy(feedbackDefinitionsData?.content || [], "name");
  }, [feedbackDefinitionsData?.content]);

  const handleClose = useCallback(() => {
    setOpen(false);
    setScores(new Map());
    setIsSubmitting(false);
  }, [setOpen]);

  const handleScoreChange = useCallback(
    (name: string, value: number, categoryName?: string) => {
      setScores((prev) => {
        const next = new Map(prev);
        next.set(name, { name, value, categoryName });
        return next;
      });
    },
    [],
  );

  const handleScoreRemove = useCallback((name: string) => {
    setScores((prev) => {
      const next = new Map(prev);
      next.delete(name);
      return next;
    });
  }, []);

  const handleSubmit = useCallback(async () => {
    if (scores.size === 0) return;

    setIsSubmitting(true);
    const scoreEntries = Array.from(scores.values());
    const isTrace = type === TRACE_DATA_TYPE.traces;

    try {
      const promises = rows.flatMap((row) =>
        scoreEntries.map((score) =>
          setFeedbackScore({
            name: score.name,
            value: score.value,
            categoryName: score.categoryName,
            traceId: isTrace ? row.id : (row as Span).trace_id,
            spanId: isTrace ? undefined : row.id,
          }),
        ),
      );

      await Promise.all(promises);

      const entityName = isTrace ? "traces" : "spans";
      toast({
        title: "Success",
        description: `Applied ${scoreEntries.length} score${
          scoreEntries.length > 1 ? "s" : ""
        } to ${rows.length} ${entityName}`,
      });

      handleClose();
    } catch {
      toast({
        title: "Error",
        description: "Failed to apply some feedback scores. Please try again.",
        variant: "destructive",
      });
    } finally {
      setIsSubmitting(false);
    }
  }, [scores, rows, type, setFeedbackScore, toast, handleClose]);

  const entityName = type === TRACE_DATA_TYPE.traces ? "traces" : "spans";

  return (
    <Dialog open={Boolean(open)} onOpenChange={handleClose}>
      <DialogContent className="max-h-[80vh] sm:max-w-[550px]">
        <DialogHeader>
          <DialogTitle>
            Annotate {rows.length} {entityName}
          </DialogTitle>
          <DialogDescription>
            Set feedback scores that will be applied to all {rows.length}{" "}
            selected {entityName}.
          </DialogDescription>
        </DialogHeader>
        <div className="max-h-[50vh] overflow-y-auto py-2">
          {feedbackDefinitions.length === 0 ? (
            <div className="comet-body-s px-2 py-4 text-muted-slate">
              No feedback definitions configured. Please set up feedback
              definitions in the Configuration page first.
            </div>
          ) : (
            <div className="grid max-w-full grid-cols-[minmax(0,5fr)_minmax(0,5fr)_30px] border-b border-border empty:border-transparent">
              {feedbackDefinitions.map((definition) => (
                <BatchAnnotateRow
                  key={definition.name}
                  feedbackDefinition={definition}
                  scoreEntry={scores.get(definition.name)}
                  onScoreChange={handleScoreChange}
                  onScoreRemove={handleScoreRemove}
                />
              ))}
            </div>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button
            onClick={handleSubmit}
            disabled={scores.size === 0 || isSubmitting}
          >
            {isSubmitting
              ? "Applying..."
              : `Apply to ${rows.length} ${entityName}`}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

type BatchAnnotateRowProps = {
  feedbackDefinition: FeedbackDefinition;
  scoreEntry?: ScoreEntry;
  onScoreChange: (name: string, value: number, categoryName?: string) => void;
  onScoreRemove: (name: string) => void;
};

const BatchAnnotateRow: React.FunctionComponent<BatchAnnotateRowProps> = ({
  feedbackDefinition,
  scoreEntry,
  onScoreChange,
  onScoreRemove,
}) => {
  const name = feedbackDefinition.name;
  const value = scoreEntry ? scoreEntry.value : ("" as const);
  const categoryName = scoreEntry?.categoryName ?? "";

  const renderOptions = () => {
    if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.numerical) {
      return (
        <DebounceInput
          className="my-0.5 h-7 min-w-[100px] py-1"
          max={feedbackDefinition.details.max}
          min={feedbackDefinition.details.min}
          step="any"
          dimension="sm"
          delay={300}
          onValueChange={(val) => {
            const newValue = val === "" ? "" : Number(val);

            if (newValue === "") {
              onScoreRemove(name);
              return;
            }

            if (
              isNumericFeedbackScoreValid(feedbackDefinition.details, newValue)
            ) {
              onScoreChange(name, newValue);
            }
          }}
          placeholder="Score"
          type="number"
          value={value}
        />
      );
    }

    if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.boolean) {
      const onBooleanValueChange = (val?: string) => {
        if (!val) {
          onScoreRemove(name);
          return;
        }
        const boolValue = val === feedbackDefinition.details.true_label ? 1 : 0;
        onScoreChange(name, boolValue, val);
      };

      return (
        <ToggleGroup
          className="min-w-fit p-0.5"
          onValueChange={onBooleanValueChange}
          variant="outline"
          type="single"
          size="md"
          value={categoryName}
        >
          <ToggleGroupItem
            className="w-full"
            key="true"
            value={feedbackDefinition.details.true_label}
          >
            <div className="text-nowrap">
              {feedbackDefinition.details.true_label}
            </div>
          </ToggleGroupItem>
          <ToggleGroupItem
            className="w-full"
            key="false"
            value={feedbackDefinition.details.false_label}
          >
            <div className="text-nowrap">
              {feedbackDefinition.details.false_label}
            </div>
          </ToggleGroupItem>
        </ToggleGroup>
      );
    }

    if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.categorical) {
      const onCategoricalValueChange = (val?: string) => {
        if (val === "") {
          onScoreRemove(name);
          return;
        }

        const categoryEntry = Object.entries(
          feedbackDefinition.details.categories,
        ).find(([catName]) => catName === val);

        if (categoryEntry) {
          onScoreChange(name, categoryEntry[1], val);
        }
      };

      const categoricalOptionList = sortBy(
        Object.entries(feedbackDefinition.details.categories).map(
          ([catName, catValue]) => ({
            name: catName,
            value: catValue,
          }),
        ),
        "value",
      );

      const hasLongNames = categoricalOptionList.some((item) => {
        const label = categoryOptionLabelRenderer(item.name, item.value);
        return label.length > 10;
      });
      const hasMultipleOptions = categoricalOptionList.length > 2;

      if (hasLongNames || hasMultipleOptions) {
        const categoricalSelectOptionList = categoricalOptionList.map(
          (item) => ({
            label: item.name,
            value: item.name,
            description: String(item.value),
          }),
        );
        return (
          <SelectBox
            value={categoryName || ""}
            options={categoricalSelectOptionList}
            onChange={onCategoricalValueChange}
            className="my-0.5 h-7 min-w-[100px] py-1"
            renderTrigger={(val) => {
              const selectedOption = categoricalOptionList.find(
                (item) => item.name.trim() === val.trim(),
              );

              if (!selectedOption) {
                return <div className="truncate">Select a category</div>;
              }

              return (
                <span className="text-nowrap">
                  {categoryOptionLabelRenderer(val, selectedOption.value)}
                </span>
              );
            }}
            renderOption={(option: DropdownOption<string>) => (
              <SelectItem key={option.value} value={option.value}>
                {categoryOptionLabelRenderer(option.value, option.description)}
              </SelectItem>
            )}
          />
        );
      }

      return (
        <ToggleGroup
          className="min-w-fit p-0.5"
          onValueChange={onCategoricalValueChange}
          variant="outline"
          type="single"
          size="md"
          value={String(categoryName)}
        >
          {categoricalOptionList.map(({ name: catName, value: catValue }) => {
            return (
              <ToggleGroupItem className="w-full" key={catName} value={catName}>
                <div className="text-nowrap">
                  {catName} ({catValue})
                </div>
              </ToggleGroupItem>
            );
          })}
        </ToggleGroup>
      );
    }

    return null;
  };

  return (
    <>
      <div className="flex items-center overflow-hidden border-t border-border p-1 pl-0">
        <ColoredTagNew label={name} />
      </div>
      <div
        className="flex items-center overflow-hidden border-t border-border p-1"
        data-test-value={name}
      >
        <div className="min-w-0 flex-1 overflow-auto">{renderOptions()}</div>
      </div>
      <div className="flex items-center overflow-hidden border-t border-border px-0.5">
        {isNumber(value) && (
          <Button
            variant="minimal"
            size="icon-xs"
            onClick={() => onScoreRemove(name)}
          >
            <X />
          </Button>
        )}
      </div>
    </>
  );
};

export default BatchAnnotateDialog;
