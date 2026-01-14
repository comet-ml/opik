import React, { useCallback, useMemo } from "react";
import { UseFormReturn } from "react-hook-form";
import { Plus } from "lucide-react";
import uniqid from "uniqid";

import { Button } from "@/components/ui/button";
import { FormField, FormItem } from "@/components/ui/form";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { Filter } from "@/types/filters";
import {
  COLUMN_TYPE,
  ColumnData,
  DropdownOption,
  COLUMN_METADATA_ID,
  COLUMN_FEEDBACK_SCORES_ID,
} from "@/types/shared";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { OPERATORS_MAP } from "@/constants/filters";
import { createFilter } from "@/lib/filters";
import FiltersContent from "@/components/shared/FiltersContent/FiltersContent";
import TracesOrSpansPathsAutocomplete from "@/components/pages-shared/traces/TracesOrSpansPathsAutocomplete/TracesOrSpansPathsAutocomplete";
import TracesOrSpansFeedbackScoresSelect from "@/components/pages-shared/traces/TracesOrSpansFeedbackScoresSelect/TracesOrSpansFeedbackScoresSelect";
import { ANNOTATION_QUEUE_SCOPE } from "@/types/annotation-queues";
import { Description } from "@/components/ui/description";
import { FilterOperator } from "@/types/filters";
import { ThreadStatus } from "@/types/thread";

// Trace-specific columns for annotation queue filtering
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
];

// Thread-specific columns for annotation queue filtering
export const THREAD_FILTER_COLUMNS: ColumnData<TRACE_DATA_TYPE>[] = [
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

// Thread status options for the status filter
const THREAD_STATUS_OPTIONS = Object.values(ThreadStatus).map((status) => ({
  label: status.charAt(0).toUpperCase() + status.slice(1).toLowerCase(),
  value: status,
}));

interface StatusSelectProps {
  value: string;
  onValueChange: (value: string) => void;
}

const StatusSelect: React.FC<StatusSelectProps> = ({
  value,
  onValueChange,
}) => (
  <select
    value={value}
    onChange={(e) => onValueChange(e.target.value)}
    className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
  >
    <option value="">Select status</option>
    {THREAD_STATUS_OPTIONS.map((option) => (
      <option key={option.value} value={option.value}>
        {option.label}
      </option>
    ))}
  </select>
);

interface AnnotationQueueFilterSectionProps {
  form: UseFormReturn<{
    filter_criteria: Filter[];
    scope: ANNOTATION_QUEUE_SCOPE;
    project_id: string;
    [key: string]: unknown;
  }>;
  disabled?: boolean;
}

const AnnotationQueueFilterSection: React.FC<
  AnnotationQueueFilterSectionProps
> = ({ form, disabled = false }) => {
  const scope = form.watch("scope");
  const projectId = form.watch("project_id");
  const isTraceScope = scope === ANNOTATION_QUEUE_SCOPE.TRACE;
  const filtersFromForm = form.watch("filter_criteria");
  const filters = useMemo(() => filtersFromForm || [], [filtersFromForm]);

  const currentFilterColumns = useMemo(() => {
    return isTraceScope ? TRACE_FILTER_COLUMNS : THREAD_FILTER_COLUMNS;
  }, [isTraceScope]);

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
            type: TRACE_DATA_TYPE.traces,
            placeholder: "key",
            excludeRoot: true,
          },
          operators: ruleDictionaryOperators,
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
        ...(!isTraceScope
          ? {
              status: {
                defaultOperator: "=" as FilterOperator,
                operators: [
                  {
                    label: "=",
                    value: "=" as FilterOperator,
                  },
                ],
                valueComponent: StatusSelect,
              },
            }
          : {}),
      },
    }),
    [projectId, isTraceScope, ruleDictionaryOperators],
  );

  const handleAddFilter = useCallback(() => {
    const newFilter = createFilter(uniqid());
    form.setValue("filter_criteria", [...filters, newFilter]);
  }, [filters, form]);

  const handleRemoveFilter = useCallback(
    (id: string) => {
      form.setValue(
        "filter_criteria",
        filters.filter((f) => f.id !== id),
      );
    },
    [filters, form],
  );

  const handleChangeFilter = useCallback(
    (id: string, filter: Filter) => {
      form.setValue(
        "filter_criteria",
        filters.map((f) => (f.id === id ? filter : f)),
      );
    },
    [filters, form],
  );

  const hasFilters = filters.length > 0;

  return (
    <Accordion
      type="single"
      collapsible
      className="w-full"
      defaultValue={hasFilters ? "filters" : undefined}
    >
      <AccordionItem value="filters" className="border-b-0">
        <AccordionTrigger className="py-2 hover:no-underline">
          <div className="flex items-center gap-2">
            <span className="comet-body-s text-muted-slate">
              Dynamic queue criteria (optional)
            </span>
            {hasFilters && (
              <span className="rounded-full bg-primary px-2 py-0.5 text-xs text-primary-foreground">
                {filters.length}
              </span>
            )}
          </div>
        </AccordionTrigger>
        <AccordionContent className="pt-2">
          <Description className="mb-4">
            Define filter criteria to automatically add matching{" "}
            {isTraceScope ? "traces" : "threads"} to this queue. Items will be
            evaluated when created or modified. Leave empty for a manual queue.
          </Description>

          <FormField
            control={form.control}
            name="filter_criteria"
            render={() => (
              <FormItem>
                <div className="space-y-4">
                  {hasFilters && (
                    <FiltersContent
                      columns={currentFilterColumns}
                      filters={filters}
                      onRemoveRow={handleRemoveFilter}
                      onChangeRow={handleChangeFilter}
                      config={filtersConfig}
                      disabled={disabled}
                    />
                  )}
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={handleAddFilter}
                    disabled={disabled}
                  >
                    <Plus className="mr-2 size-4" />
                    Add filter
                  </Button>
                </div>
              </FormItem>
            )}
          />
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default AnnotationQueueFilterSection;
