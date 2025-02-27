import React, { useCallback, useEffect, useRef, useState } from "react";
import isUndefined from "lodash/isUndefined";
import isNumber from "lodash/isNumber";
import sortBy from "lodash/sortBy";
import { MessageSquareMore, X } from "lucide-react";

import useTraceFeedbackScoreSetMutation from "@/api/traces/useTraceFeedbackScoreSetMutation";
import useTraceFeedbackScoreDeleteMutation from "@/api/traces/useTraceFeedbackScoreDeleteMutation";
import DebounceInput from "@/components/shared/DebounceInput/DebounceInput";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import {
  FEEDBACK_DEFINITION_TYPE,
  FeedbackDefinition,
} from "@/types/feedback-definitions";
import { TraceFeedbackScore } from "@/types/traces";
import { Button } from "@/components/ui/button";
import { isNumericFeedbackScoreValid } from "@/lib/traces";
import ColoredTagNew from "@/components/shared/ColoredTag/ColoredTagNew";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { SelectItem } from "@/components/ui/select";
import { DropdownOption } from "@/types/shared";
import { cn, updateTextAreaHeight } from "@/lib/utils";
import { Textarea } from "@/components/ui/textarea";
import { useDebouncedValue } from "@/hooks/useDebouncedValue";
import { categoryOptionLabelRenderer } from "@/lib/feedback-scores";

const SET_VALUE_DEBOUNCE_DELAY = 500;

type AnnotateRowProps = {
  name: string;
  feedbackDefinition?: FeedbackDefinition;
  feedbackScore?: TraceFeedbackScore;
  spanId?: string;
  traceId: string;
};

const AnnotateRow: React.FunctionComponent<AnnotateRowProps> = ({
  name,
  feedbackDefinition,
  feedbackScore,
  spanId,
  traceId,
}) => {
  const textAreaRef = useRef<HTMLTextAreaElement | null>(null);

  const [categoryName, setCategoryName] = useState(
    feedbackScore?.category_name,
  );
  const [editReason, setEditReason] = useState(false);

  useEffect(() => {
    setCategoryName(feedbackScore?.category_name);
    setEditReason(false);
  }, [feedbackScore?.category_name, traceId, spanId]);

  useEffect(() => {
    setEditReason(false);
  }, [traceId, spanId]);

  const [value, setValue] = useState<number | "">(
    isNumber(feedbackScore?.value) ? feedbackScore?.value : "",
  );
  useEffect(() => {
    setValue(isNumber(feedbackScore?.value) ? feedbackScore?.value : "");
  }, [feedbackScore?.value, spanId, traceId]);

  const handleChangeReason = useCallback(
    (reason?: string) => {
      setTraceFeedbackScoreMutation.mutate({
        categoryName,
        name,
        spanId,
        traceId,
        reason,
        value: value as number,
      });
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [name, value, spanId, traceId],
  );

  const onChangeTextAreaTriggered = useCallback(() => {
    updateTextAreaHeight(textAreaRef.current);
  }, []);

  const { value: reasonValue, onChange: onReasonChange } = useDebouncedValue({
    initialValue: feedbackScore?.reason,
    onDebouncedChange: handleChangeReason,
    delay: SET_VALUE_DEBOUNCE_DELAY,
    onChangeTriggered: onChangeTextAreaTriggered,
  });

  const feedbackScoreDeleteMutation = useTraceFeedbackScoreDeleteMutation();

  const deleteFeedbackScore = useCallback(() => {
    feedbackScoreDeleteMutation.mutate({
      traceId,
      spanId,
      name: feedbackScore?.name ?? "",
    });
    setEditReason(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [feedbackScore?.name, traceId, spanId]);

  const setTraceFeedbackScoreMutation = useTraceFeedbackScoreSetMutation();

  const handleChangeValue = useCallback(
    (value: number, categoryName?: string) => {
      setTraceFeedbackScoreMutation.mutate({
        categoryName,
        name,
        spanId,
        traceId,
        value,
      });
    },
    // setTraceFeedbackScoreMutation re triggers this memo when it should not
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [name, spanId, traceId],
  );

  const renderOptions = (feedbackDefinition: FeedbackDefinition) => {
    if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.numerical) {
      return (
        <DebounceInput
          className="h-7 min-w-[100px] py-1"
          max={feedbackDefinition.details.max}
          min={feedbackDefinition.details.min}
          step="any"
          dimension="sm"
          delay={SET_VALUE_DEBOUNCE_DELAY}
          onValueChange={(value) => {
            const newValue = value === "" ? "" : Number(value);

            setValue(newValue);

            if (newValue === "") {
              deleteFeedbackScore();
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

    if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.categorical) {
      const onCategoricalValueChange = (value?: string) => {
        if (value === "") {
          deleteFeedbackScore();
          return;
        }

        const categoryEntry = Object.entries(
          feedbackDefinition.details.categories,
        ).find(([categoryName]) => categoryName === value);

        if (categoryEntry) {
          const categoryValue = categoryEntry[1];

          setCategoryName(value);
          setValue(categoryValue);
          handleChangeValue(categoryValue, value);
        }
      };
      const categoricalOptionList = sortBy(
        Object.entries(feedbackDefinition.details.categories).map(
          ([name, value]) => ({
            name,
            value,
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
            className="h-7 min-w-[100px] py-1"
            renderTrigger={(value) => {
              const selectedOption = categoricalOptionList.find(
                (item) => item.name === value,
              );

              if (!selectedOption) {
                return <span className="text-nowrap">Select a category</span>;
              }

              return (
                <span className="text-nowrap">
                  {categoryOptionLabelRenderer(value, selectedOption.value)}
                </span>
              );
            }}
            renderOption={(option: DropdownOption<string>) => (
              <SelectItem key={option.value} value={option.value}>
                {categoryOptionLabelRenderer(option.value, option.description)}
              </SelectItem>
            )}
          ></SelectBox>
        );
      }
      return (
        <ToggleGroup
          className="min-w-fit"
          onValueChange={onCategoricalValueChange}
          variant="outline"
          type="single"
          size="md"
          value={String(categoryName)}
        >
          {categoricalOptionList.map(({ name, value }) => {
            return (
              <ToggleGroupItem className="w-full" key={name} value={name}>
                <div className="text-nowrap">
                  {name} ({value})
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
        {feedbackDefinition ? (
          <div className="min-w-0 flex-1 overflow-auto">
            {renderOptions(feedbackDefinition)}
          </div>
        ) : (
          <div>{feedbackScore?.value}</div>
        )}
      </div>
      <div className="flex items-center justify-center overflow-hidden border-t border-border">
        {!isUndefined(feedbackScore?.value) && (
          <Button
            variant="outline"
            size="icon-sm"
            className={cn(
              "size-7 relative group/reason-btn",
              editReason &&
                "bg-[#F3F4FE] active:bg-[#F3F4FE] hover:bg-[#F3F4FE]",
            )}
            onClick={() => setEditReason((v) => !v)}
          >
            {!isUndefined(reasonValue) && (
              <div
                className={cn(
                  "absolute right-1 top-1 size-[8px] rounded-full border-2 border-white bg-primary group-hover/reason-btn:border-primary-foreground",
                  editReason &&
                    "border-[#F3F4FE] group-hover/reason-btn:border-[#F3F4FE]",
                )}
              />
            )}
            <MessageSquareMore className="size-3.5" />
          </Button>
        )}
      </div>
      <div className="flex items-center overflow-hidden border-t border-border">
        {!isUndefined(feedbackScore?.value) && (
          <Button
            variant="minimal"
            size="icon-sm"
            onClick={deleteFeedbackScore}
          >
            <X />
          </Button>
        )}
      </div>

      {editReason && (
        <>
          <div className="col-span-3 px-1 pb-1">
            <Textarea
              placeholder="Add a reason..."
              value={reasonValue}
              onChange={onReasonChange}
              className="min-h-12 resize-none overflow-hidden"
              ref={(e) => {
                textAreaRef.current = e;
                updateTextAreaHeight(e, 48);
              }}
            />
          </div>
          <div></div>
        </>
      )}
    </>
  );
};

export default AnnotateRow;
