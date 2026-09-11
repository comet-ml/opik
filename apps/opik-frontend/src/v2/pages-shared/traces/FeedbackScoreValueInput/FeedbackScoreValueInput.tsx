import React, { useCallback, useMemo } from "react";
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

export type FeedbackScoreValueInputProps = {
  feedbackDefinition: FeedbackDefinition;
  value: number | string;
  categoryName?: string;
  onChange: (update: FeedbackScoreValue) => void;
  testIdPrefix?: string;
  disabled?: boolean;
  debounceDelay?: number;
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
  debounceDelay = SET_VALUE_DEBOUNCE_DELAY,
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
        const lastValidNumeric = typeof value === "number" ? value : undefined;
        onChange({
          value: lastValidNumeric,
          status: "invalid",
        });
        return;
      }
      onChange({ value: num, status: "valid" });
    },
    [feedbackDefinition, onChange, value],
  );

  if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.numerical) {
    return (
      <DebounceInput
        className="my-0.5 h-7 min-w-[100px] py-1"
        max={feedbackDefinition.details.max}
        min={feedbackDefinition.details.min}
        step="any"
        dimension="sm"
        delay={debounceDelay}
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
    // Use stable internal sentinel values ("true" / "false") so that the
    // toggle works correctly even when true_label === false_label.
    const BOOL_TRUE = "true";
    const BOOL_FALSE = "false";

    const onBooleanValueChange = (internal?: string) => {
      if (!internal) {
        return;
      }
      const isTrue = internal === BOOL_TRUE;
      onChange({
        value: isTrue ? 1 : 0,
        categoryName: isTrue
          ? feedbackDefinition.details.true_label
          : feedbackDefinition.details.false_label,
        status: "valid",
      });
    };

    // Map the current numeric value back to the internal sentinel for
    // controlled selection.  When value is not 0 or 1, nothing is selected.
    const boolInternalValue =
      value === 1 ? BOOL_TRUE : value === 0 ? BOOL_FALSE : undefined;

    const areLabelsIdentical =
      feedbackDefinition.details.true_label ===
      feedbackDefinition.details.false_label;

    return (
      <ToggleGroup
        className="min-w-fit p-0.5"
        onValueChange={onBooleanValueChange}
        variant="outline"
        type="single"
        size="md"
        value={boolInternalValue}
        disabled={disabled}
      >
        <ToggleGroupItem
          className="w-full"
          key={BOOL_TRUE}
          value={BOOL_TRUE}
          data-testid={
            areLabelsIdentical
              ? `${prefix}-boolean-toggle-true`
              : `${prefix}-boolean-toggle-${feedbackDefinition.details.true_label}`
          }
        >
          <div className="text-nowrap">
            {feedbackDefinition.details.true_label}
          </div>
        </ToggleGroupItem>
        <ToggleGroupItem
          className="w-full"
          key={BOOL_FALSE}
          value={BOOL_FALSE}
          data-testid={
            areLabelsIdentical
              ? `${prefix}-boolean-toggle-false`
              : `${prefix}-boolean-toggle-${feedbackDefinition.details.false_label}`
          }
        >
          <div className="text-nowrap">
            {feedbackDefinition.details.false_label}
          </div>
        </ToggleGroupItem>
      </ToggleGroup>
    );
  }

  if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.categorical) {
    return (
      <CategoricalFeedbackScoreInput
        feedbackDefinition={feedbackDefinition}
        value={value}
        categoryName={categoryName}
        onChange={onChange}
        prefix={prefix}
        disabled={disabled}
      />
    );
  }

  return null;
};

type CategoricalFeedbackScoreInputProps = {
  feedbackDefinition: FeedbackDefinition;
  value: number | string;
  categoryName?: string;
  onChange: (update: FeedbackScoreValue) => void;
  prefix: string;
  disabled: boolean;
};

const CategoricalFeedbackScoreInput: React.FunctionComponent<
  CategoricalFeedbackScoreInputProps
> = ({
  feedbackDefinition,
  value,
  categoryName,
  onChange,
  prefix,
  disabled,
}) => {
  const onCategoricalValueChange = (label?: string) => {
    // An empty-string category can be a legitimate key; only treat "" as
    // "clear" when no such key exists in the definition.
    const categories =
      (feedbackDefinition.details as { categories?: Record<string, number> })
        .categories || {};
    const hasEmptyKey = Object.prototype.hasOwnProperty.call(categories, "");
    if ((label === undefined || label === "") && !hasEmptyKey) {
      onChange({
        value: undefined,
        categoryName: undefined,
        status: "empty",
      });
      return;
    }
    const categoryEntry = Object.entries(categories).find(
      ([name]) => name === label,
    );

    if (categoryEntry) {
      onChange({
        value: categoryEntry[1],
        categoryName: label,
        status: "valid",
      });
    }
  };

  // Memoize expensive O(n log n) sort + scan so re-renders caused by
  // upstream fields (e.g. the "Reason" textarea) don't repeat this work.
  const {
    categoricalOptionList,
    hasLongLabels,
    hasMoreThanTwoOptions,
    categoriesMap,
  } = useMemo(() => {
    const rawMap =
      (feedbackDefinition.details as { categories?: Record<string, number> })
        .categories || {};
    const optionList = sortBy(
      Object.entries(rawMap).map(([name, val]) => ({ name, value: val })),
      "value",
    );
    const longLabels = optionList.some((item) => {
      const label = categoryOptionLabelRenderer(item.name, item.value);
      return label.length > 10;
    });
    return {
      categoricalOptionList: optionList,
      hasLongLabels: longLabels,
      hasMoreThanTwoOptions: optionList.length > 2,
      categoriesMap: rawMap,
    };
  }, [feedbackDefinition]);

  const isUnscored =
    value === "" ||
    value === undefined ||
    value === null ||
    categoryName === undefined ||
    (categoryName === "" && (value as unknown) === "");

  const hasExplicitCategorySelection =
    !isUnscored &&
    categoryName !== undefined &&
    Object.prototype.hasOwnProperty.call(categoriesMap, categoryName);

  const encodedSelectedValue = hasExplicitCategorySelection
    ? encodeCategoryOptionValue(categoryName, categoriesMap)
    : "";

  if (hasLongLabels || hasMoreThanTwoOptions) {
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
          if (!val) {
            return <div className="truncate">Select a category</div>;
          }
          const actualName = decodeCategoryOptionValue(val, categoriesMap);
          if (actualName === undefined) {
            return <div className="truncate">Select a category</div>;
          }
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

  const toggleValue = hasExplicitCategorySelection
    ? encodeCategoryOptionValue(categoryName, categoriesMap)
    : undefined;

  return (
    <ToggleGroup
      className="min-w-fit p-0.5"
      onValueChange={(val) => {
        onCategoricalValueChange(decodeCategoryOptionValue(val, categoriesMap));
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
};

export default FeedbackScoreValueInput;
