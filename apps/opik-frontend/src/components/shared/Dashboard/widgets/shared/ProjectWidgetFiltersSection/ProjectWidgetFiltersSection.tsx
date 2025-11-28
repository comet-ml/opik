import React, { useCallback, useEffect, useMemo } from "react";
import {
  Control,
  FieldPath,
  FieldValues,
  useController,
} from "react-hook-form";
import { Plus } from "lucide-react";
import isArray from "lodash/isArray";

import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Description } from "@/components/ui/description";
import { FormErrorSkeleton, FormField, FormItem } from "@/components/ui/form";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { Filter } from "@/types/filters";
import { ColumnData } from "@/types/shared";
import { createFilter } from "@/lib/filters";
import { generateRandomString } from "@/lib/utils";
import FiltersContent from "@/components/shared/FiltersContent/FiltersContent";
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

  const handleAddFilter = useCallback(() => {
    const newFilter = {
      ...createFilter(),
      id: generateRandomString(),
    };
    setFilters((prev) => [...prev, newFilter]);
  }, [setFilters]);

  return (
    <Accordion
      type="single"
      collapsible
      className={`w-full border-t ${className}`}
    >
      <AccordionItem value="filters" className="border-none">
        <AccordionTrigger className="py-3 hover:no-underline">
          <Label className="text-sm font-medium">
            {label} {filters.length > 0 && `(${filters.length})`}
          </Label>
        </AccordionTrigger>
        <AccordionContent className="space-y-3 pb-3">
          <Description>
            Add filters to focus the widget on specific properties.
          </Description>
          <FormField
            control={control}
            name={fieldName}
            render={({ field, formState }) => {
              const filterErrors = formState.errors[fieldName];
              const hasErrors = filterErrors && isArray(filterErrors);

              return (
                <FormItem>
                  <div className="space-y-3">
                    {field.value && (field.value as Filter[]).length > 0 && (
                      <FiltersContent
                        filters={field.value as Filter[]}
                        setFilters={setFilters}
                        columns={filterColumns as ColumnData<unknown>[]}
                        config={filtersConfig}
                        className="py-0"
                      />
                    )}

                    {hasErrors && (filterErrors as unknown[]).length > 0 && (
                      <div className="space-y-1">
                        {(filterErrors as unknown[]).map(
                          (filterError, index) => {
                            if (!filterError) return null;

                            const errors: string[] = [];
                            const error = filterError as Record<
                              string,
                              { message?: string }
                            >;

                            if (error.field?.message) {
                              errors.push(error.field.message);
                            }
                            if (error.operator?.message) {
                              errors.push(error.operator.message);
                            }
                            if (error.value?.message) {
                              errors.push(error.value.message);
                            }
                            if (error.key?.message) {
                              errors.push(error.key.message);
                            }

                            if (errors.length === 0) return null;

                            return (
                              <FormErrorSkeleton key={index}>
                                Filter {index + 1}: {errors.join(", ")}
                              </FormErrorSkeleton>
                            );
                          },
                        )}
                      </div>
                    )}

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
                </FormItem>
              );
            }}
          />
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default ProjectWidgetFiltersSection;
