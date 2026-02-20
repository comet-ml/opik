import React, { useCallback, useMemo, useState } from "react";
import sortBy from "lodash/sortBy";
import isNumber from "lodash/isNumber";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogAutoScrollBody,
  DialogFooter,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";
import useAppStore from "@/store/AppStore";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useTraceFeedbackScoreSetMutation from "@/api/traces/useTraceFeedbackScoreSetMutation";
import { Trace, Span } from "@/types/traces";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import {
  FeedbackDefinition,
  FEEDBACK_DEFINITION_TYPE,
} from "@/types/feedback-definitions";
import DebounceInput from "@/components/shared/DebounceInput/DebounceInput";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import ColoredTagNew from "@/components/shared/ColoredTag/ColoredTagNew";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { SelectItem } from "@/components/ui/select";
import { DropdownOption } from "@/types/shared";
import { isNumericFeedbackScoreValid } from "@/lib/traces";
import { categoryOptionLabelRenderer } from "@/lib/feedback-scores";
import { Loader2, X } from "lucide-react";

type FeedbackScoreEntry = {
  name: string;
  value: number;
  categoryName?: string;
};

type BulkAnnotateDialogProps = {
  rows: Array<Trace | Span>;
  open: boolean | number;
  setOpen: (open: boolean | number) => void;
  type: TRACE_DATA_TYPE;
};

const BulkAnnotateDialog: React.FunctionComponent<BulkAnnotateDialogProps> = ({
  rows,
  open,
  setOpen,
  type,
}) => {
  const { toast } = useToast();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [scores, setScores] = useState<Record<string, FeedbackScoreEntry>>({});
  const [isSubmitting, setIsSubmitting] = useState(false);

  const { data: feedbackDefinitionsData } = useFeedbackDefinitionsList({
    workspaceName,
    page: 1,
    size: 1000,
  });

  const { mutateAsync: setTraceFeedbackScore } =
    useTraceFeedbackScoreSetMutation();

  const feedbackDefinitions: FeedbackDefinition[] = useMemo(() => {
    return sortBy(feedbackDefinitionsData?.content || [], "name");
  }, [feedbackDefinitionsData?.content]);

  const handleClose = useCallback(() => {
    setOpen(false);
    setScores({});
  }, [setOpen]);

  const handleScoreChange = useCallback(
    (name: string, value: number, categoryName?: string) => {
      setScores((prev) => ({
        ...prev,
        [name]: { name, value, categoryName },
      }));
    },
    [],
  );

  const handleScoreRemove = useCallback((name: string) => {
    setScores((prev) => {
      const next = { ...prev };
      delete next[name];
      return next;
    });
  }, []);

  const handleSubmit = useCallback(async () => {
    const scoreEntries = Object.values(scores);
    if (scoreEntries.length === 0) return;

    setIsSubmitting(true);

    try {
      const promises = rows.flatMap((row) =>
        scoreEntries.map((score) =>
          setTraceFeedbackScore({
            traceId:
              type === TRACE_DATA_TYPE.spans ? (row as Span).trace_id : row.id,
            spanId: type === TRACE_DATA_TYPE.spans ? row.id : undefined,
            name: score.name,
            value: score.value,
            categoryName: score.categoryName,
          }),
        ),
      );

      await Promise.all(promises);

      const entityName = type === TRACE_DATA_TYPE.traces ? "traces" : "spans";
      toast({
        title: "Success",
        description: `Applied ${scoreEntries.length} feedback score${
          scoreEntries.length > 1 ? "s" : ""
        } to ${rows.length} ${entityName}`,
      });

      handleClose();
    } catch {
      // Error handling is done by the mutation hook
    } finally {
      setIsSubmitting(false);
    }
  }, [scores, rows, type, setTraceFeedbackScore, toast, handleClose]);

  const hasScores = Object.keys(scores).length > 0;
  const entityName = type === TRACE_DATA_TYPE.traces ? "traces" : "spans";

  return (
    <Dialog open={Boolean(open)} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>
            Annotate {rows.length} {entityName}
          </DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <div className="flex flex-col gap-1 px-1">
            <p className="comet-body-s pb-2 text-muted-foreground">
              Select feedback scores to apply to all selected {entityName}. Only
              scores you set below will be applied.
            </p>
            {feedbackDefinitions.length === 0 ? (
              <p className="comet-body-s py-4 text-center text-muted-foreground">
                No feedback definitions configured. Create feedback definitions
                in the Configuration page first.
              </p>
            ) : (
              <div className="grid max-w-full grid-cols-[minmax(0,5fr)_minmax(0,5fr)_30px] border-b border-border empty:border-transparent">
                {feedbackDefinitions.map((definition) => (
                  <BulkAnnotateRow
                    key={definition.name}
                    feedbackDefinition={definition}
                    currentScore={scores[definition.name]}
                    onScoreChange={handleScoreChange}
                    onScoreRemove={handleScoreRemove}
                  />
                ))}
              </div>
            )}
          </div>
        </DialogAutoScrollBody>
        <DialogFooter>
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} disabled={!hasScores || isSubmitting}>
            {isSubmitting && <Loader2 className="mr-2 size-4 animate-spin" />}
            {isSubmitting
              ? "Applying..."
              : `Apply to ${rows.length} ${entityName}`}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

type BulkAnnotateRowProps = {
  feedbackDefinition: FeedbackDefinition;
  currentScore?: FeedbackScoreEntry;
  onScoreChange: (name: string, value: number, categoryName?: string) => void;
  onScoreRemove: (name: string) => void;
};

const BulkAnnotateRow: React.FunctionComponent<BulkAnnotateRowProps> = ({
  feedbackDefinition,
  currentScore,
  onScoreChange,
  onScoreRemove,
}) => {
  const name = feedbackDefinition.name;

  const [value, setValue] = useState<number | "">(
    currentScore ? currentScore.value : "",
  );
  const [categoryName, setCategoryName] = useState<string | undefined>(
    currentScore?.categoryName,
  );

  const handleChangeValue = useCallback(
    (newValue: number, newCategoryName?: string) => {
      onScoreChange(name, newValue, newCategoryName);
    },
    [name, onScoreChange],
  );

  const handleDelete = useCallback(() => {
    setValue("");
    setCategoryName(undefined);
    onScoreRemove(name);
  }, [name, onScoreRemove]);

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
          onValueChange={(v) => {
            const newValue = v === "" ? "" : Number(v);
            setValue(newValue);
            if (newValue === "") {
              handleDelete();
              return;
            }
            if (
              isNumericFeedbackScoreValid(feedbackDefinition.details, newValue)
            ) {
              handleChangeValue(newValue as number);
            }
          }}
          placeholder="Score"
          type="number"
          value={value}
        />
      );
    }

    if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.boolean) {
      const onBooleanValueChange = (v?: string) => {
        const boolValue = v === feedbackDefinition.details.true_label ? 1 : 0;
        setCategoryName(v);
        setValue(boolValue);
        handleChangeValue(boolValue, v);
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
      const onCategoricalValueChange = (v?: string) => {
        if (v === "") {
          handleDelete();
          return;
        }

        const categoryEntry = Object.entries(
          feedbackDefinition.details.categories,
        ).find(([catName]) => catName === v);

        if (categoryEntry) {
          const categoryValue = categoryEntry[1];
          setCategoryName(v);
          setValue(categoryValue);
          handleChangeValue(categoryValue, v);
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
            renderTrigger={(v) => {
              const selectedOption = categoricalOptionList.find(
                (item) => item.name.trim() === v.trim(),
              );

              if (!selectedOption) {
                return <div className="truncate">Select a category</div>;
              }

              return (
                <span className="text-nowrap">
                  {categoryOptionLabelRenderer(v, selectedOption.value)}
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
          {categoricalOptionList.map(({ name: catName, value: catValue }) => (
            <ToggleGroupItem className="w-full" key={catName} value={catName}>
              <div className="text-nowrap">
                {catName} ({catValue})
              </div>
            </ToggleGroupItem>
          ))}
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
          <Button variant="minimal" size="icon-xs" onClick={handleDelete}>
            <X />
          </Button>
        )}
      </div>
    </>
  );
};

export default BulkAnnotateDialog;
