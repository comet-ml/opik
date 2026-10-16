import React, { useCallback, useState } from "react";
import { UseFormReturn } from "react-hook-form";
import { ListFilter } from "lucide-react";
import round from "lodash/round";

import { FormErrorSkeleton, FormField } from "@/ui/form";
import { Filter } from "@/types/filters";
import { EVAL_TRIGGER_SCOPE } from "@/types/automations";
import FiltersContent from "@/shared/FiltersContent/FiltersContent";
import SliderInputControl from "@/shared/SliderInputControl/SliderInputControl";
import AddItemButton from "@/shared/AddItemButton/AddItemButton";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import RuleSettingsCard from "@/v2/pages-shared/automations/RuleEditorPanel/RuleSettingsCard";
import useRuleFiltersConfig from "@/v2/pages-shared/automations/RuleEditorPanel/useRuleFiltersConfig";
import {
  createEmptyRuleFilter,
  withEmptyRuleFilter,
} from "@/v2/pages-shared/automations/RuleEditorPanel/helpers";
import {
  DEFAULT_SAMPLING_RATE,
  FILTER_COLUMNS_BY_SCOPE,
} from "@/v2/pages-shared/automations/RuleEditorPanel/constants";

type FilteringSamplingCardProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
  projectId: string;
};

const FilteringSamplingCard: React.FC<FilteringSamplingCardProps> = ({
  form,
  projectId,
}) => {
  const scope = form.watch("scope");
  const triggerScope = form.watch("triggerScope");
  const filtersConfig = useRuleFiltersConfig(scope, projectId);
  const [defaultOpen] = useState(
    () =>
      form.getValues("filters").some((filter) => filter.field !== "") ||
      form.getValues("samplingRate") < 1,
  );

  const setFilters = useCallback(
    (filtersOrUpdater: Filter[] | ((prev: Filter[]) => Filter[])) => {
      const filters =
        typeof filtersOrUpdater === "function"
          ? filtersOrUpdater(form.getValues("filters"))
          : filtersOrUpdater;
      form.setValue("filters", withEmptyRuleFilter(filters));
      form.clearErrors("filters");
    },
    [form],
  );

  const addFilter = useCallback(
    () => setFilters((prev) => [...prev, createEmptyRuleFilter()]),
    [setFilters],
  );

  if (triggerScope === EVAL_TRIGGER_SCOPE.experiment) {
    return null;
  }

  return (
    <RuleSettingsCard
      icon={ListFilter}
      iconClassName="bg-lime-400"
      title="Filtering and sampling"
      tooltip={
        triggerScope === EVAL_TRIGGER_SCOPE.both
          ? "Filters and sampling apply only to production traces, not to experiment traces."
          : undefined
      }
      collapsible
      defaultOpen={defaultOpen}
    >
      <FormField
        control={form.control}
        name="filters"
        render={({ field, formState }) => {
          const filterErrors = Array.isArray(formState.errors.filters)
            ? formState.errors.filters
            : [];

          return (
            <div className="flex flex-col gap-1.5 px-2.5 py-2">
              <span className="comet-body-xs-accented pl-px text-muted-slate">
                Filters
              </span>
              {field.value.length > 0 && (
                <FiltersContent
                  filters={field.value}
                  setFilters={setFilters}
                  columns={FILTER_COLUMNS_BY_SCOPE[scope]}
                  config={filtersConfig}
                  className="py-0"
                  variant="card"
                />
              )}
              {filterErrors.map((filterError, index) => {
                const messages = [
                  filterError?.field?.message,
                  filterError?.operator?.message,
                  filterError?.value?.message,
                  filterError?.key?.message,
                ].filter(Boolean);

                return messages.length > 0 ? (
                  <FormErrorSkeleton key={index}>
                    Filter {index + 1}: {messages.join(", ")}
                  </FormErrorSkeleton>
                ) : null;
              })}
              <AddItemButton label="Add filter" onClick={addFilter} />
            </div>
          );
        }}
      />
      <div className="border-t border-border px-2.5 py-2">
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
              suffix="%"
              size="sm"
            />
          )}
        />
      </div>
    </RuleSettingsCard>
  );
};

export default FilteringSamplingCard;
