import React, { useCallback } from "react";
import sortBy from "lodash/sortBy";
import DebounceInput from "@/shared/DebounceInput/DebounceInput";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import {
  FEEDBACK_DEFINITION_TYPE,
  FeedbackDefinition,
} from "@/types/feedback-definitions";
import { isNumericFeedbackScoreValid } from "@/lib/traces";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { SelectItem } from "@/ui/select";
import { DropdownOption } from "@/types/shared";
import { categoryOptionLabelRenderer } from "@/lib/feedback-scores";

export const SET_VALUE_DEBOUNCE_DELAY = 500;

export type FeedbackScoreValue = {
  value?: number;
  categoryName?: string;
  /**
   * Why the numeric value changed: "empty" = the user cleared the input,
   * "invalid" = out-of-range/non-numeric input was rejected, "valid" = a
   * usable value was entered. Callers use this to distinguish clearing a
   * score (delete) from merely ignoring bad input.
   */
  status?: "empty" | "invalid" | "valid";
};

type FeedbackScoreValueInputProps = {
  feedbackDefinition: FeedbackDefinition;
  value: number | "";
  categoryName?: string;
  onChange: (update: FeedbackScoreValue) => void;
  testIdPrefix?: string;
};

/**
 * Controlled input for a feedback definition value.
 *
 * Encodes the shared domain rules for numerical (range validation), boolean
 * (label/value mapping) and categorical (category/value mapping) feedback
 * scores. Callers keep their own draft state and persistence callbacks; a
 * change with `value: undefined` means "clear the score".
 */
const FeedbackScoreValueInput: React.FunctionComponent<
  FeedbackScoreValueInputProps
> = ({ feedbackDefinition, value, categoryName, onChange, testIdPrefix }) => {
  const prefix = testIdPrefix ?? "feedback-score-value";

  const handleNumericChange = useCallback(
    (inputValue: string | number | readonly string[] | undefined) => {
      if (inputValue === undefined || inputValue === "") {
        onChange({ value: undefined, status: "empty" });
        return;
      }
      const num = Number(inputValue);
      if (
        typeof num !== "number" ||
        Number.isNaN(num) ||
        !isNumericFeedbackScoreValid(
          feedbackDefinition.details as { min: number; max: number },
          num,
        )
      ) {
        onChange({ value: undefined, status: "invalid" });
        return;
      }
      onChange({ value: num, status: "valid" });
    },
    [feedbackDefinition, onChange],
  );

  if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.numerical) {
    return (
      <DebounceInput
        className="my-0.5 h-7 min-w-[100px] py-1"
        max={feedbackDefinition.details.max}
        min={feedbackDefinition.details.min}
        step="any"
        dimension="sm"
        delay={SET_VALUE_DEBOUNCE_DELAY}
        onValueChange={handleNumericChange}
        placeholder="Score"
        type="number"
        value={value}
        aria-label={feedbackDefinition.name}
        data-testid={`${prefix}-score-input`}
      />
    );
  }

  if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.boolean) {
    const onBooleanValueChange = (label?: string) => {
      if (!label) {
        return;
      }
      onChange({
        value: label === feedbackDefinition.details.true_label ? 1 : 0,
        categoryName: label,
      });
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
          data-testid={`${prefix}-boolean-toggle-${feedbackDefinition.details.true_label}`}
        >
          <div className="text-nowrap">
            {feedbackDefinition.details.true_label}
          </div>
        </ToggleGroupItem>
        <ToggleGroupItem
          className="w-full"
          key="false"
          value={feedbackDefinition.details.false_label}
          data-testid={`${prefix}-boolean-toggle-${feedbackDefinition.details.false_label}`}
        >
          <div className="text-nowrap">
            {feedbackDefinition.details.false_label}
          </div>
        </ToggleGroupItem>
      </ToggleGroup>
    );
  }

  if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.categorical) {
    const onCategoricalValueChange = (label?: string) => {
      // An empty-string category can be a legitimate key; only treat "" as
      // "clear" when no such key exists in the definition.
      const hasEmptyKey = Object.prototype.hasOwnProperty.call(
        feedbackDefinition.details.categories,
        "",
      );
      if (!label && !hasEmptyKey) {
        onChange({ value: undefined, categoryName: undefined });
        return;
      }
      const categoryEntry = Object.entries(
        feedbackDefinition.details.categories,
      ).find(([name]) => name === label);

      if (categoryEntry) {
        onChange({ value: categoryEntry[1], categoryName: label });
      }
    };

    const categoricalOptionList = sortBy(
      Object.entries(feedbackDefinition.details.categories).map(
        ([name, val]) => ({
          name,
          value: val,
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
      const categoricalSelectOptionList = categoricalOptionList.map((item) => ({
        label: item.name,
        value: item.name,
        description: String(item.value),
      }));
      return (
        <SelectBox
          value={categoryName || ""}
          options={categoricalSelectOptionList}
          onChange={onCategoricalValueChange}
          className="my-0.5 h-7 min-w-[100px] py-1"
          testId={`${prefix}-category-select`}
          renderTrigger={(val) => {
            const selectedOption = categoricalOptionList.find(
              (item) => item.name.trim() === (val || "").trim(),
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
        {categoricalOptionList.map(({ name, value: categoryValue }) => {
          return (
            <ToggleGroupItem
              className="w-full"
              key={name}
              value={name}
              data-testid={`${prefix}-category-toggle-${name}`}
            >
              <div className="text-nowrap">
                {categoryOptionLabelRenderer(name, categoryValue)}
              </div>
            </ToggleGroupItem>
          );
        })}
      </ToggleGroup>
    );
  }

  return null;
};

export default FeedbackScoreValueInput;
