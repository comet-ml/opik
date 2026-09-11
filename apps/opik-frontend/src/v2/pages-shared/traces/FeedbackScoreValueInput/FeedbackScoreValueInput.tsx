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

export const getCollisionFreeEmptyCategorySentinel = (
  categories: Record<string, number>,
): string => {
  let sentinel = "__EMPTY_CATEGORY_SENTINEL__";
  while (Object.prototype.hasOwnProperty.call(categories, sentinel)) {
    sentinel = `_${sentinel}_`;
  }
  return sentinel;
};

export const encodeCategoryOptionValue = (
  name: string,
  categories: Record<string, number>,
): string => {
  if (name === "") {
    return getCollisionFreeEmptyCategorySentinel(categories);
  }
  return name;
};

export const decodeCategoryOptionValue = (
  value: string | undefined,
  categories: Record<string, number>,
): string | undefined => {
  if (value === undefined) {
    return undefined;
  }
  const sentinel = getCollisionFreeEmptyCategorySentinel(categories);
  if (value === sentinel) {
    return "";
  }
  return value;
};

export type FeedbackScoreValue = {
  value?: number;
  categoryName?: string;
  /**
   * Why the value changed: "empty" = user explicitly cleared input,
   * "invalid" = out-of-range/non-numeric input was rejected,
   * "valid" = a valid usable value was entered.
   */
  status?: "empty" | "invalid" | "valid";
};

type FeedbackScoreValueInputProps = {
  feedbackDefinition: FeedbackDefinition;
  value: number | "";
  categoryName?: string;
  onChange: (update: FeedbackScoreValue) => void;
  testIdPrefix?: string;
  disabled?: boolean;
};

/**
 * Controlled input for a feedback definition value.
 * Encodes the shared domain rules for numerical (range validation), boolean
 * (label/value mapping), and categorical (category/value mapping) feedback scores.
 */
const FeedbackScoreValueInput: React.FunctionComponent<
  FeedbackScoreValueInputProps
> = ({
  feedbackDefinition,
  value,
  categoryName,
  onChange,
  testIdPrefix,
  disabled = false,
}) => {
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
        disabled={disabled}
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
        status: "valid",
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
        disabled={disabled}
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
      if ((label === undefined || label === "") && !hasEmptyKey) {
        onChange({ value: undefined, categoryName: undefined, status: "empty" });
        return;
      }
      const categoryEntry = Object.entries(
        feedbackDefinition.details.categories,
      ).find(([name]) => name === label);

      if (categoryEntry) {
        onChange({
          value: categoryEntry[1],
          categoryName: label,
          status: "valid",
        });
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

    const categoriesMap = feedbackDefinition.details.categories || {};
    const encodedSelectedValue =
      categoryName !== undefined
        ? encodeCategoryOptionValue(categoryName, categoriesMap)
        : "";

    if (hasLongNames || hasMultipleOptions) {
      const categoricalSelectOptionList = categoricalOptionList.map((item) => ({
        label: item.name === "" ? '""' : item.name,
        value: encodeCategoryOptionValue(item.name, categoriesMap),
        description: String(item.value),
      }));

      const handleSelectChange = (encodedVal?: string) => {
        const decoded = decodeCategoryOptionValue(encodedVal, categoriesMap);
        onCategoricalValueChange(decoded);
      };

      return (
        <SelectBox
          value={encodedSelectedValue}
          options={categoricalSelectOptionList}
          onChange={handleSelectChange}
          className="my-0.5 h-7 min-w-[100px] py-1"
          testId={`${prefix}-category-select`}
          disabled={disabled}
          renderTrigger={(val) => {
            const actualName =
              decodeCategoryOptionValue(val, categoriesMap) ?? "";
            const selectedOption = categoricalOptionList.find(
              (item) => item.name === actualName,
            );

            if (!selectedOption) {
              return <div className="truncate">Select a category</div>;
            }

            return (
              <span className="text-nowrap">
                {categoryOptionLabelRenderer(
                  selectedOption.name,
                  selectedOption.value,
                )}
              </span>
            );
          }}
          renderOption={(option: DropdownOption<string>) => {
            const actualName =
              decodeCategoryOptionValue(option.value, categoriesMap) ?? "";
            return (
              <SelectItem key={option.value} value={option.value}>
                {categoryOptionLabelRenderer(actualName, option.description)}
              </SelectItem>
            );
          }}
        />
      );
    }

    const toggleValue =
      categoryName !== undefined
        ? encodeCategoryOptionValue(categoryName, categoriesMap)
        : undefined;

    return (
      <ToggleGroup
        className="min-w-fit p-0.5"
        onValueChange={(val) => {
          onCategoricalValueChange(
            decodeCategoryOptionValue(val, categoriesMap),
          );
        }}
        variant="outline"
        type="single"
        size="md"
        value={toggleValue}
        disabled={disabled}
      >
        {categoricalOptionList.map(({ name, value: categoryValue }) => {
          const itemVal = encodeCategoryOptionValue(name, categoriesMap);
          return (
            <ToggleGroupItem
              className="w-full"
              key={itemVal}
              value={itemVal}
              data-testid={`${prefix}-category-toggle-${name || "empty"}`}
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
