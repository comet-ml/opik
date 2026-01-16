import React from "react";
import get from "lodash/get";
import isArray from "lodash/isArray";
import isFunction from "lodash/isFunction";
import { Filter, ListChecks } from "lucide-react";
import {
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Description } from "@/components/ui/description";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import ExperimentsSelectBox from "@/components/pages-shared/experiments/ExperimentsSelectBox/ExperimentsSelectBox";
import FiltersAccordionSection from "@/components/shared/FiltersAccordionSection/FiltersAccordionSection";
import { Control } from "react-hook-form";
import { cn } from "@/lib/utils";
import { DASHBOARD_TEMPLATES } from "@/lib/dashboard/templates";
import { TEMPLATE_TYPE, EXPERIMENT_DATA_SOURCE } from "@/types/dashboard";
import { Filters } from "@/types/filters";
import {
  COLUMN_DATASET_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { FiltersArraySchema } from "@/components/shared/FiltersAccordionSection/schema";
import { z } from "zod";
import DashboardTemplateCard from "./DashboardTemplateCard";

type ExperimentColumnData = {
  id: string;
  dataset_id?: string;
};

const EXPERIMENT_FILTER_COLUMNS: ColumnData<ExperimentColumnData>[] = [
  {
    id: COLUMN_DATASET_ID,
    label: "Dataset",
    type: COLUMN_TYPE.string,
    disposable: true,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    iconType: "tags",
  },
  {
    id: COLUMN_METADATA_ID,
    label: "Configuration",
    type: COLUMN_TYPE.dictionary,
  },
];

interface DashboardFormFields {
  name: string;
  description?: string;
  projectId?: string;
  experimentIds?: string[];
  templateType?: string;
  experimentDataSource?: EXPERIMENT_DATA_SOURCE;
  experimentFilters?: z.infer<typeof FiltersArraySchema>;
  maxExperimentsCount?: number;
}

interface DashboardDialogDetailsStepProps {
  control: Control<DashboardFormFields>;
  templateType?: string;
  showDataSourceSection?: boolean;
  descriptionExpanded?: boolean;
  onSubmit: () => void;
  experimentDataSource?: EXPERIMENT_DATA_SOURCE;
  experimentFilters?: Filters;
  onExperimentDataSourceChange?: (value: EXPERIMENT_DATA_SOURCE) => void;
  onExperimentFiltersChange?: (filters: Filters) => void;
  onMaxExperimentsCountChange?: (value: number | undefined) => void;
}

const DashboardDialogDetailsStep: React.FunctionComponent<
  DashboardDialogDetailsStepProps
> = ({
  control,
  templateType,
  showDataSourceSection,
  descriptionExpanded,
  onSubmit,
  experimentDataSource = EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
  experimentFilters = [],
  onExperimentDataSourceChange,
  onExperimentFiltersChange,
  onMaxExperimentsCountChange,
}) => {
  const template = templateType
    ? DASHBOARD_TEMPLATES[templateType as TEMPLATE_TYPE]
    : null;

  return (
    <div className="flex flex-col gap-4">
      {template && (
        <FormField
          control={control}
          name="templateType"
          render={() => (
            <FormItem>
              <FormLabel>Template</FormLabel>
              <DashboardTemplateCard
                name={template.name}
                description={template.description}
                icon={template.icon}
                iconColor={template.iconColor}
              />
            </FormItem>
          )}
        />
      )}

      <FormField
        control={control}
        name="name"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["name"]);

          return (
            <FormItem>
              <FormLabel>Name</FormLabel>
              <FormControl>
                <Input
                  {...field}
                  placeholder="Dashboard name"
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") {
                      e.preventDefault();
                      onSubmit();
                    }
                  }}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          );
        }}
      />

      <Accordion
        type="multiple"
        defaultValue={descriptionExpanded ? ["description"] : []}
        className="border-t"
      >
        <AccordionItem value="description">
          <AccordionTrigger className="h-11 py-1.5">
            Description
          </AccordionTrigger>
          <AccordionContent className="px-3">
            <Textarea
              {...control.register("description")}
              placeholder="Dashboard description"
              maxLength={255}
            />
          </AccordionContent>
        </AccordionItem>
      </Accordion>

      {showDataSourceSection && (
        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-1">
            <h4 className="comet-body-accented">Default data source</h4>
            <Description>
              Choose default project and experiments to preview data in this
              dashboard. Individual widgets can override these settings if
              needed.
            </Description>
          </div>

          <FormField
            control={control}
            name="projectId"
            render={({ field, formState }) => {
              const validationErrors = get(formState.errors, ["projectId"]);

              return (
                <FormItem>
                  <FormLabel>Default project (optional)</FormLabel>
                  <FormControl>
                    <ProjectsSelectBox
                      value={field.value || ""}
                      onValueChange={field.onChange}
                      showClearButton
                      className={cn("flex-1", {
                        "border-destructive": Boolean(
                          validationErrors?.message,
                        ),
                      })}
                    />
                  </FormControl>
                  <Description>Used to preview data by default.</Description>
                  <FormMessage />
                </FormItem>
              );
            }}
          />

          <div className="flex flex-col gap-1">
            <h4 className="comet-body-accented">Experiment data source</h4>
            <Description>
              Choose how to select experiments for experiment widgets.
            </Description>
          </div>

          <FormField
            control={control}
            name="experimentDataSource"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Selection method</FormLabel>
                <FormControl>
                  <ToggleGroup
                    type="single"
                    variant="ghost"
                    value={
                      field.value || EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS
                    }
                    onValueChange={(value) => {
                      if (value) {
                        field.onChange(value);
                        onExperimentDataSourceChange?.(
                          value as EXPERIMENT_DATA_SOURCE,
                        );
                      }
                    }}
                    className="w-fit justify-start"
                  >
                    <ToggleGroupItem
                      value={EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS}
                      aria-label="Manual selection"
                      className="gap-1.5"
                    >
                      <ListChecks className="size-3.5" />
                      <span>Manual selection</span>
                    </ToggleGroupItem>
                    <ToggleGroupItem
                      value={EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP}
                      aria-label="Filter experiments"
                      className="gap-1.5"
                    >
                      <Filter className="size-3.5" />
                      <span>Filter experiments</span>
                    </ToggleGroupItem>
                  </ToggleGroup>
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          {experimentDataSource ===
            EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS && (
            <FormField
              control={control}
              name="experimentIds"
              render={({ field, formState }) => {
                const validationErrors = get(formState.errors, [
                  "experimentIds",
                ]);

                return (
                  <FormItem>
                    <FormLabel>Default experiments (optional)</FormLabel>
                    <FormControl>
                      <ExperimentsSelectBox
                        value={field.value || []}
                        onValueChange={field.onChange}
                        multiselect
                        showClearButton
                        className={cn("flex-1", {
                          "border-destructive": Boolean(
                            validationErrors?.message,
                          ),
                        })}
                      />
                    </FormControl>
                    <Description>
                      Used by widgets that show experiment data.
                    </Description>
                    <FormMessage />
                  </FormItem>
                );
              }}
            />
          )}

          {experimentDataSource === EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP && (
            <>
              <FiltersAccordionSection
                filters={isArray(experimentFilters) ? experimentFilters : []}
                columns={EXPERIMENT_FILTER_COLUMNS as ColumnData<unknown>[]}
                onChange={(filtersOrUpdater) => {
                  if (onExperimentFiltersChange) {
                    const updatedFilters = isFunction(filtersOrUpdater)
                      ? filtersOrUpdater(
                          isArray(experimentFilters) ? experimentFilters : [],
                        )
                      : filtersOrUpdater;
                    onExperimentFiltersChange(updatedFilters);
                  }
                }}
              />

              <FormField
                control={control}
                name="maxExperimentsCount"
                render={({ field, formState }) => {
                  const validationErrors = get(formState.errors, [
                    "maxExperimentsCount",
                  ]);
                  return (
                    <FormItem>
                      <FormLabel>Maximum experiments</FormLabel>
                      <FormControl>
                        <Input
                          type="number"
                          min={1}
                          max={100}
                          value={field.value ?? ""}
                          onChange={(e) => {
                            const value = e.target.value;
                            const numValue = value
                              ? parseInt(value, 10)
                              : undefined;
                            field.onChange(numValue);
                            onMaxExperimentsCountChange?.(numValue);
                          }}
                          className={cn({
                            "border-destructive": Boolean(
                              validationErrors?.message,
                            ),
                          })}
                        />
                      </FormControl>
                      <Description>
                        Maximum number of experiments to display (1-100)
                      </Description>
                      <FormMessage />
                    </FormItem>
                  );
                }}
              />
            </>
          )}
        </div>
      )}
    </div>
  );
};

export default DashboardDialogDetailsStep;
