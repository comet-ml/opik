import React, { useCallback, useEffect, useMemo } from "react";
import { UseFormReturn } from "react-hook-form";
import { Plus } from "lucide-react";
import uniqid from "uniqid";
import round from "lodash/round";
import isArray from "lodash/isArray";

import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { FormErrorSkeleton, FormField, FormItem } from "@/components/ui/form";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { Filter } from "@/types/filters";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import {
  COLUMN_METADATA_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_CUSTOM_ID,
} from "@/types/shared";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import {
  CUSTOM_FILTER_VALIDATION_REGEXP,
  OPERATORS_MAP,
} from "@/constants/filters";
import { createFilter } from "@/lib/filters";
import { ThreadStatus } from "@/types/thread";
import FiltersContent from "@/components/shared/FiltersContent/FiltersContent";
import TracesOrSpansPathsAutocomplete from "@/components/pages-shared/traces/TracesOrSpansPathsAutocomplete/TracesOrSpansPathsAutocomplete";
import TracesOrSpansFeedbackScoresSelect from "@/components/pages-shared/traces/TracesOrSpansFeedbackScoresSelect/TracesOrSpansFeedbackScoresSelect";
import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { EvaluationRuleFormType } from "./schema";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { Description } from "@/components/ui/description";
import { SPAN_TYPE } from "@/types/traces";
import { SPAN_TYPE_LABELS_MAP } from "@/constants/traces";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { DropdownOption } from "@/types/shared";
import { FilterOperator } from "@/types/filters";
import { SelectItem } from "@/components/ui/select";
import BaseTraceDataTypeIcon from "@/components/pages-shared/traces/TraceDetailsPanel/BaseTraceDataTypeIcon";

// Trace-specific columns for automation rule filtering
export const TRACE_FILTER_COLUMNS: ColumnData<TRACE_DATA_TYPE>[] = [
  {
    id: "id",
    label: "ID",
    type: COLUMN_TYPE.string,
  },
  {
    id: "name",
    label: "Name",
    type: COLUMN_TYPE.string,
  },
  {
    id: "input",
    label: "Input",
    type: COLUMN_TYPE.string,
  },
  {
    id: "output",
    label: "Output",
    type: COLUMN_TYPE.string,
  },
  {
    id: "duration",
    label: "Duration",
    type: COLUMN_TYPE.duration,
  },
  {
    id: COLUMN_METADATA_ID,
    label: "Metadata",
    type: COLUMN_TYPE.dictionary,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    iconType: "tags",
  },
  {
    id: "thread_id",
    label: "Thread ID",
    type: COLUMN_TYPE.string,
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
  // {
  //   id: COLUMN_CUSTOM_ID,
  //   label: "Custom filter",
  //   type: COLUMN_TYPE.dictionary,
  // },
];

// Thread-specific columns for automation rule filtering
export const THREAD_FILTER_COLUMNS: ColumnData<TRACE_DATA_TYPE>[] = [
  // {
  //   id: "id",
  //   label: "ID",
  //   type: COLUMN_TYPE.string,
  // },
  {
    id: "status",
    label: "Status",
    type: COLUMN_TYPE.string,
  },
  {
    id: "created_at",
    label: "Created at",
    type: COLUMN_TYPE.time,
  },
  {
    id: "last_updated_at",
    label: "Last updated at",
    type: COLUMN_TYPE.time,
  },
  // {
  //   id: "start_time",
  //   label: "Start time",
  //   type: COLUMN_TYPE.time,
  // },
  // {
  //   id: "end_time",
  //   label: "End time",
  //   type: COLUMN_TYPE.time,
  // },
  {
    id: "duration",
    label: "Duration",
    type: COLUMN_TYPE.duration,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    iconType: "tags",
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
];

// Span-specific columns for automation rule filtering
export const SPAN_FILTER_COLUMNS: ColumnData<TRACE_DATA_TYPE>[] = [
  {
    id: "id",
    label: "ID",
    type: COLUMN_TYPE.string,
  },
  {
    id: "name",
    label: "Name",
    type: COLUMN_TYPE.string,
  },
  {
    id: "input",
    label: "Input",
    type: COLUMN_TYPE.string,
  },
  {
    id: "output",
    label: "Output",
    type: COLUMN_TYPE.string,
  },
  {
    id: "duration",
    label: "Duration",
    type: COLUMN_TYPE.duration,
  },
  {
    id: COLUMN_METADATA_ID,
    label: "Metadata",
    type: COLUMN_TYPE.dictionary,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    iconType: "tags",
  },
  {
    id: "type",
    label: "Type",
    type: COLUMN_TYPE.category,
  },
  {
    id: "model",
    label: "Model",
    type: COLUMN_TYPE.string,
  },
  {
    id: "provider",
    label: "Provider",
    type: COLUMN_TYPE.string,
  },
  {
    id: "usage.total_tokens",
    label: "Total tokens",
    type: COLUMN_TYPE.number,
  },
  {
    id: "usage.prompt_tokens",
    label: "Total input tokens",
    type: COLUMN_TYPE.number,
  },
  {
    id: "usage.completion_tokens",
    label: "Total output tokens",
    type: COLUMN_TYPE.number,
  },
  {
    id: "total_estimated_cost",
    label: "Estimated cost",
    type: COLUMN_TYPE.cost,
  },
  {
    id: "error_info",
    label: "Errors",
    type: COLUMN_TYPE.errors,
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
  {
    id: COLUMN_CUSTOM_ID,
    label: "Custom filter",
    type: COLUMN_TYPE.dictionary,
  },
];

// Exported for backward compatibility
export const AUTOMATION_RULE_FILTER_COLUMNS = TRACE_FILTER_COLUMNS;

const DEFAULT_SAMPLING_RATE = 1;

interface RuleFilteringSectionProps {
  form: UseFormReturn<EvaluationRuleFormType>;
  projectId: string;
}

const RuleFilteringSection: React.FC<RuleFilteringSectionProps> = ({
  form,
  projectId,
}) => {
  const scope = form.watch("scope");
  const isTraceScope = scope === EVALUATORS_RULE_SCOPE.trace;
  const isThreadScope = scope === EVALUATORS_RULE_SCOPE.thread;
  const isSpanScope = scope === EVALUATORS_RULE_SCOPE.span;
  const filters = form.watch("filters");
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );

  const currentFilterColumns = useMemo(() => {
    if (isThreadScope) return THREAD_FILTER_COLUMNS;
    if (isSpanScope) return SPAN_FILTER_COLUMNS;
    return TRACE_FILTER_COLUMNS;
  }, [isThreadScope, isSpanScope]);

  useEffect(() => {
    if (form.formState.errors.filters) {
      form.clearErrors("filters");
    }
  }, [filters.length, form]);

  // Rule-specific operators for dictionary filters (includes is_empty and is_not_empty)
  const ruleDictionaryOperators: DropdownOption<FilterOperator>[] = useMemo(
    () => [
      ...(OPERATORS_MAP[COLUMN_TYPE.dictionary] || []),
      {
        label: "is empty",
        value: "is_empty",
      },
      {
        label: "is not empty",
        value: "is_not_empty",
      },
    ],
    [],
  );

  const filtersConfig = useMemo(
    () => ({
      rowsMap: {
        [COLUMN_METADATA_ID]: {
          keyComponent: TracesOrSpansPathsAutocomplete as React.FC<unknown> & {
            placeholder: string;
            value: string;
            onValueChange: (value: string) => void;
          },
          keyComponentProps: {
            rootKeys: ["metadata"],
            projectId,
            type: isSpanScope ? TRACE_DATA_TYPE.spans : TRACE_DATA_TYPE.traces,
            placeholder: "key",
            excludeRoot: true,
          },
          operators: ruleDictionaryOperators,
        },
        [COLUMN_CUSTOM_ID]: {
          keyComponent: TracesOrSpansPathsAutocomplete as React.FC<unknown> & {
            placeholder: string;
            value: string;
            onValueChange: (value: string) => void;
          },
          keyComponentProps: {
            rootKeys: ["input", "output"],
            projectId,
            type: isSpanScope ? TRACE_DATA_TYPE.spans : TRACE_DATA_TYPE.traces,
            placeholder: "key",
            excludeRoot: false,
          },
          operators: ruleDictionaryOperators,
          validateFilter: (filter: Filter) => {
            if (
              filter.key &&
              filter.value &&
              !CUSTOM_FILTER_VALIDATION_REGEXP.test(filter.key)
            ) {
              return `Key is invalid, it should be "input", "output", and follow this format: "input.[PATH]" For example: "input.message" or just "input" for the whole object`;
            }
          },
        },
        [COLUMN_FEEDBACK_SCORES_ID]: {
          keyComponent:
            TracesOrSpansFeedbackScoresSelect as React.FC<unknown> & {
              placeholder: string;
              value: string;
              onValueChange: (value: string) => void;
            },
          keyComponentProps: {
            projectId,
            type: isSpanScope ? TRACE_DATA_TYPE.spans : TRACE_DATA_TYPE.traces,
            placeholder: "Select score",
          },
        },
        ...(isThreadScope
          ? {
              status: {
                keyComponentProps: {
                  options: [
                    { value: ThreadStatus.ACTIVE, label: "Active" },
                    { value: ThreadStatus.INACTIVE, label: "Inactive" },
                  ],
                  placeholder: "Select status",
                },
              },
            }
          : {}),
        ...(isSpanScope
          ? {
              type: {
                keyComponentProps: {
                  options: [
                    {
                      value: SPAN_TYPE.general,
                      label: SPAN_TYPE_LABELS_MAP[SPAN_TYPE.general],
                    },
                    {
                      value: SPAN_TYPE.tool,
                      label: SPAN_TYPE_LABELS_MAP[SPAN_TYPE.tool],
                    },
                    {
                      value: SPAN_TYPE.llm,
                      label: SPAN_TYPE_LABELS_MAP[SPAN_TYPE.llm],
                    },
                    ...(isGuardrailsEnabled
                      ? [
                          {
                            value: SPAN_TYPE.guardrail,
                            label: SPAN_TYPE_LABELS_MAP[SPAN_TYPE.guardrail],
                          },
                        ]
                      : []),
                  ],
                  placeholder: "Select type",
                  renderOption: (option: DropdownOption<SPAN_TYPE>) => {
                    return (
                      <SelectItem
                        key={option.value}
                        value={option.value}
                        withoutCheck
                        wrapperAsChild={true}
                      >
                        <div className="flex w-full items-center gap-1.5">
                          <BaseTraceDataTypeIcon type={option.value} />
                          {option.label}
                        </div>
                      </SelectItem>
                    );
                  },
                },
              },
            }
          : {}),
      },
    }),
    [
      projectId,
      isThreadScope,
      isSpanScope,
      isGuardrailsEnabled,
      ruleDictionaryOperators,
    ],
  );

  const handleAddFilter = useCallback(() => {
    const currentFilters = form.getValues("filters");
    const newFilter = {
      ...createFilter(),
      id: uniqid(),
    };
    form.setValue("filters", [...currentFilters, newFilter]);
  }, [form]);

  const setFilters = useCallback(
    (filtersOrUpdater: Filter[] | ((prev: Filter[]) => Filter[])) => {
      if (typeof filtersOrUpdater === "function") {
        const currentFilters = form.getValues("filters");
        const updatedFilters = filtersOrUpdater(currentFilters);
        form.setValue("filters", updatedFilters);
      } else {
        form.setValue("filters", filtersOrUpdater);
      }
    },
    [form],
  );

  return (
    <Accordion
      type="single"
      collapsible
      className="-mb-4 w-full border-t border-border"
    >
      <AccordionItem value="filtering-sampling" className="border-none">
        <AccordionTrigger className="px-3 py-2 hover:no-underline">
          <div className="flex items-center gap-1">
            <Label className="text-sm font-medium">Filtering & Sampling</Label>
            <ExplainerIcon
              className="mt-0.5"
              description={
                isTraceScope
                  ? "Apply filters and sampling to select which traces will be evaluated by this rule"
                  : isThreadScope
                    ? "Use sampling rate to control how frequently this rule is applied to threads"
                    : "Apply filters and sampling to select which spans will be evaluated by this rule"
              }
            />
          </div>
        </AccordionTrigger>
        <AccordionContent className="px-3 pb-3">
          <div className="mb-8 space-y-4">
            <Description>
              Use sampling rate to control how frequently this rule is applied.
              You can also add filters to select specific{" "}
              {scope === EVALUATORS_RULE_SCOPE.trace
                ? "traces"
                : scope === EVALUATORS_RULE_SCOPE.thread
                  ? "threads"
                  : "spans"}{" "}
              based on their properties. If nothing is defined, the rule will
              evaluate all{" "}
              {scope === EVALUATORS_RULE_SCOPE.trace
                ? "traces"
                : scope === EVALUATORS_RULE_SCOPE.thread
                  ? "threads"
                  : "spans"}
              .
            </Description>

            <FormField
              control={form.control}
              name="filters"
              render={({ field, formState }) => {
                const filterErrors = formState.errors.filters;
                const hasErrors = filterErrors && isArray(filterErrors);

                return (
                  <FormItem>
                    <div className="space-y-3">
                      <Label className="text-sm font-medium">Filters</Label>

                      {field.value.length > 0 && (
                        <FiltersContent
                          filters={field.value}
                          setFilters={setFilters}
                          columns={currentFilterColumns}
                          config={filtersConfig}
                          className="py-0"
                        />
                      )}

                      {/* Display validation errors from form submission */}
                      {hasErrors && filterErrors.length > 0 && (
                        <div className="space-y-1">
                          {filterErrors.map((filterError, index) => {
                            if (!filterError) return null;

                            const errors: string[] = [];

                            // Collect all error messages for this filter
                            if (filterError.field?.message) {
                              errors.push(filterError.field.message);
                            }
                            if (filterError.operator?.message) {
                              errors.push(filterError.operator.message);
                            }
                            if (filterError.value?.message) {
                              errors.push(filterError.value.message);
                            }
                            if (filterError.key?.message) {
                              errors.push(filterError.key.message);
                            }

                            if (errors.length === 0) return null;

                            return (
                              <FormErrorSkeleton key={index}>
                                Filter {index + 1}: {errors.join(", ")}
                              </FormErrorSkeleton>
                            );
                          })}
                        </div>
                      )}

                      <div className="pt-1">
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={handleAddFilter}
                          className="w-fit"
                        >
                          <Plus className="mr-1 size-3.5" />
                          Add filter
                        </Button>
                      </div>
                    </div>
                  </FormItem>
                );
              }}
            />

            {/* Sampling Rate */}
            <FormField
              control={form.control}
              name="samplingRate"
              render={({ field }) => (
                <SliderInputControl
                  min={0}
                  max={100}
                  step={1}
                  defaultValue={DEFAULT_SAMPLING_RATE * 100}
                  value={round((field.value ?? DEFAULT_SAMPLING_RATE) * 100, 1)}
                  onChange={(displayValue) =>
                    field.onChange(round(displayValue, 1) / 100)
                  }
                  id="sampling_rate"
                  label="Sampling rate"
                  tooltip="Percentage of traces to evaluate"
                  suffix="%"
                />
              )}
            />
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default RuleFilteringSection;
