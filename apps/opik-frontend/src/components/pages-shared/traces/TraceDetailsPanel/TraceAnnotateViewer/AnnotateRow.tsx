import React, { useCallback, useEffect, useState } from "react";
import isUndefined from "lodash/isUndefined";
import isNumber from "lodash/isNumber";
import sortBy from "lodash/sortBy";
import { X } from "lucide-react";

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

const SET_VALUE_DEBOUNCE_DELAY = 500;

type AnnotateRowProps = {
  name: string;
  feedbackDefinition?: FeedbackDefinition;
  feedbackScore?: TraceFeedbackScore;
  spanId?: string;
  traceId: string;
};

const categoryOptionLabelRenderer = (name: string, value?: number | string) => {
  if (!value) return name;

  return `${name} (${value})`;
};

const AnnotateRow: React.FunctionComponent<AnnotateRowProps> = ({
  name,
  feedbackDefinition,
  feedbackScore,
  spanId,
  traceId,
}) => {
  const [categoryName, setCategoryName] = useState(
    feedbackScore?.category_name,
  );
  useEffect(() => {
    setCategoryName(feedbackScore?.category_name);
  }, [feedbackScore?.category_name, traceId, spanId]);

  const [value, setValue] = useState<number | "">(
    isNumber(feedbackScore?.value) ? feedbackScore?.value : "",
  );
  useEffect(() => {
    setValue(isNumber(feedbackScore?.value) ? feedbackScore?.value : "");
  }, [feedbackScore?.value, spanId, traceId]);

  const feedbackScoreDeleteMutation = useTraceFeedbackScoreDeleteMutation();

  const deleteFeedbackScore = useCallback(() => {
    feedbackScoreDeleteMutation.mutate({
      traceId,
      spanId,
      name: feedbackScore?.name ?? "",
    });
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
                return "Select a category";
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
      <div className="flex items-center overflow-hidden border-b border-border p-1 pl-0">
        <ColoredTagNew label={name} />
      </div>
      <div
        className="flex items-center overflow-hidden border-b border-border p-1"
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
      <div className="flex items-center overflow-hidden border-b border-border">
        {!isUndefined(feedbackScore?.value) && (
          <Button
            variant="minimal"
            size="icon-sm"
            onClick={deleteFeedbackScore}
          >
            <X className="size-4" />
          </Button>
        )}
      </div>
    </>
  );
};

export default AnnotateRow;
