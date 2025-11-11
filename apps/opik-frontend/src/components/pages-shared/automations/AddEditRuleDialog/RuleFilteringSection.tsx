import React, { useCallback, useEffect, useMemo } from "react";
import { useTranslation } from "react-i18next";
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
import { CUSTOM_FILTER_VALIDATION_REGEXP } from "@/constants/filters";
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

// Hook to get translated trace filter columns
export const useTraceFilterColumns = (): ColumnData<TRACE_DATA_TYPE>[] => {
  const { t, i18n } = useTranslation();
  
  return useMemo(() => [
    {
      id: "id",
      label: t("onlineEvaluation.dialog.filterColumns.id"),
      type: COLUMN_TYPE.string,
    },
    {
      id: "name",
      label: t("onlineEvaluation.dialog.filterColumns.name"),
      type: COLUMN_TYPE.string,
    },
    {
      id: "input",
      label: t("onlineEvaluation.dialog.filterColumns.input"),
      type: COLUMN_TYPE.string,
    },
    {
      id: "output",
      label: t("onlineEvaluation.dialog.filterColumns.output"),
      type: COLUMN_TYPE.string,
    },
    {
      id: "duration",
      label: t("onlineEvaluation.dialog.filterColumns.duration"),
      type: COLUMN_TYPE.duration,
    },
    {
      id: COLUMN_METADATA_ID,
      label: t("onlineEvaluation.dialog.filterColumns.metadata"),
      type: COLUMN_TYPE.dictionary,
    },
    {
      id: "tags",
      label: t("onlineEvaluation.dialog.filterColumns.tags"),
      type: COLUMN_TYPE.list,
    },
    {
      id: "thread_id",
      label: t("onlineEvaluation.dialog.filterColumns.threadId"),
      type: COLUMN_TYPE.string,
    },
    {
      id: COLUMN_FEEDBACK_SCORES_ID,
      label: t("onlineEvaluation.dialog.filterColumns.feedbackScores"),
      type: COLUMN_TYPE.numberDictionary,
    },
  ], [t, i18n.language]);
};

// Hook to get translated thread filter columns
export const useThreadFilterColumns = (): ColumnData<TRACE_DATA_TYPE>[] => {
  const { t, i18n } = useTranslation();
  
  return useMemo(() => [
    {
      id: "status",
      label: t("onlineEvaluation.dialog.filterColumns.status"),
      type: COLUMN_TYPE.string,
    },
    {
      id: "created_at",
      label: t("onlineEvaluation.dialog.filterColumns.createdAt"),
      type: COLUMN_TYPE.time,
    },
    {
      id: "last_updated_at",
      label: t("onlineEvaluation.dialog.filterColumns.lastUpdatedAt"),
      type: COLUMN_TYPE.time,
    },
    {
      id: "duration",
      label: t("onlineEvaluation.dialog.filterColumns.duration"),
      type: COLUMN_TYPE.duration,
    },
    {
      id: "tags",
      label: t("onlineEvaluation.dialog.filterColumns.tags"),
      type: COLUMN_TYPE.list,
    },
    {
      id: COLUMN_FEEDBACK_SCORES_ID,
      label: t("onlineEvaluation.dialog.filterColumns.feedbackScores"),
      type: COLUMN_TYPE.numberDictionary,
    },
  ], [t, i18n.language]);
};

// Legacy exports (non-translated, for backward compatibility)
export const TRACE_FILTER_COLUMNS: ColumnData<TRACE_DATA_TYPE>[] = [
  { id: "id", label: "ID", type: COLUMN_TYPE.string },
  { id: "name", label: "Name", type: COLUMN_TYPE.string },
  { id: "input", label: "Input", type: COLUMN_TYPE.string },
  { id: "output", label: "Output", type: COLUMN_TYPE.string },
  { id: "duration", label: "Duration", type: COLUMN_TYPE.duration },
  { id: COLUMN_METADATA_ID, label: "Metadata", type: COLUMN_TYPE.dictionary },
  { id: "tags", label: "Tags", type: COLUMN_TYPE.list },
  { id: "thread_id", label: "Thread ID", type: COLUMN_TYPE.string },
  { id: COLUMN_FEEDBACK_SCORES_ID, label: "Feedback scores", type: COLUMN_TYPE.numberDictionary },
];

export const THREAD_FILTER_COLUMNS: ColumnData<TRACE_DATA_TYPE>[] = [
  { id: "status", label: "Status", type: COLUMN_TYPE.string },
  { id: "created_at", label: "Created at", type: COLUMN_TYPE.time },
  { id: "last_updated_at", label: "Last updated at", type: COLUMN_TYPE.time },
  { id: "duration", label: "Duration", type: COLUMN_TYPE.duration },
  { id: "tags", label: "Tags", type: COLUMN_TYPE.list },
  { id: COLUMN_FEEDBACK_SCORES_ID, label: "Feedback scores", type: COLUMN_TYPE.numberDictionary },
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
  const { t } = useTranslation();
  const scope = form.watch("scope");
  const isTraceScope = scope === EVALUATORS_RULE_SCOPE.trace;
  const isThreadScope = scope === EVALUATORS_RULE_SCOPE.thread;
  const filters = form.watch("filters");

  // Use translated column hooks
  const traceFilterColumns = useTraceFilterColumns();
  const threadFilterColumns = useThreadFilterColumns();

  const currentFilterColumns = useMemo(() => {
    return isThreadScope ? threadFilterColumns : traceFilterColumns;
  }, [isThreadScope, threadFilterColumns, traceFilterColumns]);

  useEffect(() => {
    if (form.formState.errors.filters) {
      form.clearErrors("filters");
    }
  }, [filters.length, form]);

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
            type: TRACE_DATA_TYPE.traces,
            placeholder: "key",
            excludeRoot: true,
          },
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
            type: TRACE_DATA_TYPE.traces,
            placeholder: "key",
            excludeRoot: false,
          },
          validateFilter: (filter: Filter) => {
            if (
              filter.key &&
              filter.value &&
              !CUSTOM_FILTER_VALIDATION_REGEXP.test(filter.key)
            ) {
              return `Key is invalid, it should begin with "input", or "output" and follow this format: "input.[PATH]" For example: "input.message" `;
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
            type: TRACE_DATA_TYPE.traces,
            placeholder: t("onlineEvaluation.dialog.selectScore"),
          },
        },
        ...(isThreadScope
          ? {
              status: {
                keyComponentProps: {
                  options: [
                    { value: ThreadStatus.ACTIVE, label: t("onlineEvaluation.dialog.active") },
                    { value: ThreadStatus.INACTIVE, label: t("onlineEvaluation.dialog.inactive") },
                  ],
                  placeholder: t("onlineEvaluation.dialog.selectStatus"),
                },
              },
            }
          : {}),
      },
    }),
    [projectId, isThreadScope, t, currentFilterColumns],
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
            <Label className="text-sm font-medium">{t("onlineEvaluation.dialog.filteringSampling")}</Label>
            <ExplainerIcon
              className="mt-0.5"
              description={
                isTraceScope
                  ? t("onlineEvaluation.dialog.filteringSamplingDescTrace")
                  : t("onlineEvaluation.dialog.filteringSamplingDescThread")
              }
            />
          </div>
        </AccordionTrigger>
        <AccordionContent className="px-3 pb-3">
          <div className="mb-8 space-y-4">
            <Description>
              {t("onlineEvaluation.dialog.filteringSamplingDesc", {
                scope: scope === EVALUATORS_RULE_SCOPE.trace ? t("onlineEvaluation.dialog.traces") : t("onlineEvaluation.dialog.threads")
              })}
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
                      <Label className="text-sm font-medium">{t("onlineEvaluation.dialog.filters")}</Label>

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
                                {t("onlineEvaluation.dialog.filterError", { index: index + 1 })}: {errors.join(", ")}
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
                          {t("onlineEvaluation.dialog.addFilterButton")}
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
                  label={t("onlineEvaluation.dialog.samplingRateLabel")}
                  tooltip={t("onlineEvaluation.dialog.samplingRateTooltip")}
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
