import React, { useCallback, useEffect, useMemo } from "react";
import {
  Control,
  FieldPath,
  FieldValues,
  useController,
  useFormState,
} from "react-hook-form";
import isArray from "lodash/isArray";

import { Filter } from "@/types/filters";
import { ColumnData } from "@/types/shared";
import FiltersAccordionSection from "@/components/shared/FiltersAccordionSection/FiltersAccordionSection";
import TracesOrSpansPathsAutocomplete from "@/components/pages-shared/traces/TracesOrSpansPathsAutocomplete/TracesOrSpansPathsAutocomplete";
import TracesOrSpansFeedbackScoresSelect from "@/components/pages-shared/traces/TracesOrSpansFeedbackScoresSelect/TracesOrSpansFeedbackScoresSelect";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import {
  COLUMN_CUSTOM_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_METADATA_ID,
} from "@/types/shared";
import { ThreadStatus } from "@/types/thread";
import { CUSTOM_FILTER_VALIDATION_REGEXP } from "@/constants/filters";
import {
  TRACE_FILTER_COLUMNS,
  THREAD_FILTER_COLUMNS,
  SPAN_FILTER_COLUMNS,
} from "./constants";

interface ProjectWidgetFiltersSectionProps<T extends FieldValues> {
  control: Control<T>;
  fieldName: FieldPath<T>;
  projectId: string;
  filterType: "trace" | "thread" | "span";
  onFiltersChange?: (filters: Filter[]) => void;
  label?: string;
  className?: string;
}

const ProjectWidgetFiltersSection = <T extends FieldValues>({
  control,
  fieldName,
  projectId,
  filterType,
  onFiltersChange,
  label = "Filters",
  className = "",
}: ProjectWidgetFiltersSectionProps<T>) => {
  const { field: controllerField } = useController({
    control,
    name: fieldName,
  });

  const filters = (controllerField.value as Filter[]) || [];
  const isThreadMetric = filterType === "thread";

  const filterColumns = useMemo(() => {
    if (filterType === "thread") return THREAD_FILTER_COLUMNS;
    if (filterType === "span") return SPAN_FILTER_COLUMNS;
    return TRACE_FILTER_COLUMNS;
  }, [filterType]);

  const filtersConfig = useMemo(
    () => ({
      rowsMap: {
        [COLUMN_METADATA_ID]: {
          keyComponent:
            TracesOrSpansPathsAutocomplete as React.FunctionComponent<unknown> & {
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
          keyComponent:
            TracesOrSpansPathsAutocomplete as React.FunctionComponent<unknown> & {
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
            TracesOrSpansFeedbackScoresSelect as React.FunctionComponent<unknown> & {
              placeholder: string;
              value: string;
              onValueChange: (value: string) => void;
            },
          keyComponentProps: {
            projectId,
            type: TRACE_DATA_TYPE.traces,
            placeholder: "Select score",
          },
        },
        ...(isThreadMetric
          ? {
              status: {
                keyComponentProps: {
                  options: [
                    { value: ThreadStatus.INACTIVE, label: "Inactive" },
                    { value: ThreadStatus.ACTIVE, label: "Active" },
                  ],
                  placeholder: "Select status",
                },
              },
            }
          : {}),
      },
    }),
    [projectId, isThreadMetric],
  );

  useEffect(() => {
    const formState = control._formState;
    const fieldError = formState.errors[fieldName];
    if (fieldError && filters.length > 0) {
      control._subjects.state.next({
        ...formState,
      });
    }
  }, [filters.length, control, fieldName]);

  const setFilters = useCallback(
    (filtersOrUpdater: Filter[] | ((prev: Filter[]) => Filter[])) => {
      let updatedFilters: Filter[];

      if (typeof filtersOrUpdater === "function") {
        const currentFilters = (controllerField.value as Filter[]) || [];
        updatedFilters = filtersOrUpdater(currentFilters);
      } else {
        updatedFilters = filtersOrUpdater;
      }

      controllerField.onChange(updatedFilters);
      onFiltersChange?.(updatedFilters);
    },
    [controllerField, onFiltersChange],
  );

  const { errors: formErrors } = useFormState({ control, name: fieldName });
  const fieldErrors = formErrors[fieldName];
  const errors =
    fieldErrors && isArray(fieldErrors)
      ? (fieldErrors as unknown[]).map((e) =>
          e
            ? (e as {
                field?: { message?: string };
                operator?: { message?: string };
                value?: { message?: string };
                key?: { message?: string };
              })
            : undefined,
        )
      : undefined;

  return (
    <FiltersAccordionSection
      columns={filterColumns as ColumnData<unknown>[]}
      config={filtersConfig}
      filters={filters}
      onChange={setFilters}
      label={label}
      description="Add filters to focus the widget on specific properties."
      className={className}
      errors={errors}
    />
  );
};

export default ProjectWidgetFiltersSection;
