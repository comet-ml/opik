import React, { useCallback, useMemo } from "react";
import { useFormContext } from "react-hook-form";
import isArray from "lodash/isArray";
import isFunction from "lodash/isFunction";
import { Filter, ListChecks } from "lucide-react";
import {
  FormControl,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Description } from "@/components/ui/description";
import { Separator } from "@/components/ui/separator";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import ExperimentsSelectBox from "@/components/pages-shared/experiments/ExperimentsSelectBox/ExperimentsSelectBox";
import FiltersSection from "@/components/shared/FiltersSection/FiltersSection";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";
import { EXPERIMENT_DATA_SOURCE } from "@/types/dashboard";
import { Filters } from "@/types/filters";
import {
  COLUMN_DATASET_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";

import {
  MIN_MAX_EXPERIMENTS,
  MAX_MAX_EXPERIMENTS,
} from "@/lib/dashboard/utils";

type ExperimentColumnData = {
  id: string;
  dataset_id?: string;
};

export const EXPERIMENT_FILTER_COLUMNS: ColumnData<ExperimentColumnData>[] = [
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

interface DashboardDataSourceSectionProps {
  disableProjectSelector?: boolean;
  disableExperimentsSelector?: boolean;
  showProjectSelector?: boolean;
  className?: string;
}

const DashboardDataSourceSection: React.FC<DashboardDataSourceSectionProps> = ({
  disableProjectSelector = false,
  disableExperimentsSelector = false,
  showProjectSelector = true,
  className = "",
}) => {
  const { watch, setValue, formState } = useFormContext();

  const projectId = watch("projectId") || "";
  const experimentIds = watch("experimentIds") || [];
  const experimentDataSource =
    watch("experimentDataSource") || EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS;
  const experimentFilters = watch("experimentFilters") as Filters;
  const maxExperimentsCount = watch("maxExperimentsCount") || "";

  const filters = useMemo(
    () => (isArray(experimentFilters) ? experimentFilters : []),
    [experimentFilters],
  );

  const filterErrors = useMemo(() => {
    const fieldErrors = formState.errors.experimentFilters;
    if (!fieldErrors || !isArray(fieldErrors)) return undefined;

    return (fieldErrors as unknown[]).map((e) =>
      e
        ? (e as {
            field?: { message?: string };
            operator?: { message?: string };
            value?: { message?: string };
            key?: { message?: string };
          })
        : undefined,
    );
  }, [formState.errors.experimentFilters]);

  const maxExperimentsError = formState.errors.maxExperimentsCount?.message as
    | string
    | undefined;

  const handleProjectChange = useCallback(
    (value: string) => {
      setValue("projectId", value);
    },
    [setValue],
  );

  const handleExperimentIdsChange = useCallback(
    (value: string[]) => {
      setValue("experimentIds", value);
    },
    [setValue],
  );

  const handleDataSourceChange = useCallback(
    (value: EXPERIMENT_DATA_SOURCE) => {
      setValue("experimentDataSource", value);
    },
    [setValue],
  );

  const handleFiltersChange = useCallback(
    (filtersOrUpdater: Filters | ((prev: Filters) => Filters)) => {
      const updatedFilters = isFunction(filtersOrUpdater)
        ? filtersOrUpdater(filters)
        : filtersOrUpdater;
      setValue("experimentFilters", updatedFilters);
    },
    [setValue, filters],
  );

  const handleMaxExperimentsCountChange = useCallback(
    (value: string) => {
      setValue("maxExperimentsCount", value);
    },
    [setValue],
  );

  const renderProjectSelector = () => {
    const selectBox = (
      <ProjectsSelectBox
        value={projectId}
        onValueChange={handleProjectChange}
        minWidth={280}
        disabled={disableProjectSelector}
        className="flex-1"
        showClearButton
      />
    );

    if (disableProjectSelector) {
      return (
        <TooltipWrapper content="Project is inherited from the traces page">
          <div>{selectBox}</div>
        </TooltipWrapper>
      );
    }

    return selectBox;
  };

  const renderExperimentsSelector = () => {
    const selectBox = (
      <ExperimentsSelectBox
        value={experimentIds}
        onValueChange={handleExperimentIdsChange}
        multiselect
        minWidth={280}
        disabled={disableExperimentsSelector}
        className="flex-1"
        showClearButton
      />
    );

    if (disableExperimentsSelector) {
      return (
        <TooltipWrapper content="Experiments are inherited from the compare page">
          <div>{selectBox}</div>
        </TooltipWrapper>
      );
    }

    return selectBox;
  };

  return (
    <div className={className}>
      <Description>
        Choose default project and experiments to preview data in this
        dashboard. Individual widgets can override these settings if needed.
      </Description>
      <div className="mt-4 space-y-4">
        {showProjectSelector && (
          <>
            <FormItem>
              <FormLabel>Default project</FormLabel>
              <FormControl>{renderProjectSelector()}</FormControl>
              <Description>
                Select the default project for widgets that show project data.
              </Description>
            </FormItem>
            <Separator />
          </>
        )}

        <FormItem>
          <FormLabel>Default experiments</FormLabel>
          <FormControl>
            <TooltipWrapper
              content={
                disableExperimentsSelector
                  ? "Experiments data source is controlled by the parent page"
                  : ""
              }
            >
              <div>
                <ToggleGroup
                  type="single"
                  variant="ghost"
                  value={
                    disableExperimentsSelector
                      ? EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS
                      : experimentDataSource
                  }
                  onValueChange={(value) => {
                    if (value && !disableExperimentsSelector) {
                      handleDataSourceChange(value as EXPERIMENT_DATA_SOURCE);
                    }
                  }}
                  disabled={disableExperimentsSelector}
                  className="w-fit justify-start"
                >
                  <ToggleGroupItem
                    value={EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP}
                    aria-label="Filter experiments"
                    className="gap-1.5"
                  >
                    <Filter className="size-3.5" />
                    <span>Filter experiments</span>
                  </ToggleGroupItem>
                  <ToggleGroupItem
                    value={EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS}
                    aria-label="Manual selection"
                    className="gap-1.5"
                  >
                    <ListChecks className="size-3.5" />
                    <span>Manual selection</span>
                  </ToggleGroupItem>
                </ToggleGroup>
              </div>
            </TooltipWrapper>
          </FormControl>
        </FormItem>

        {(disableExperimentsSelector ||
          experimentDataSource ===
            EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS) && (
          <FormItem>
            <FormLabel>Select experiments</FormLabel>
            <FormControl>{renderExperimentsSelector()}</FormControl>
            <Description>
              Choose specific experiments for widgets that show experiments
              data.
            </Description>
          </FormItem>
        )}

        {!disableExperimentsSelector &&
          experimentDataSource === EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP && (
            <>
              <FiltersSection
                filters={filters}
                columns={EXPERIMENT_FILTER_COLUMNS as ColumnData<unknown>[]}
                onChange={handleFiltersChange}
                label="Filters"
                description="Filter experiments shown in widgets that use experiment data. Leave empty to include all experiments."
                errors={filterErrors}
              />
              <FormItem>
                <FormLabel>Max experiments to load</FormLabel>
                <FormControl>
                  <Input
                    type="number"
                    min={MIN_MAX_EXPERIMENTS}
                    max={MAX_MAX_EXPERIMENTS}
                    value={maxExperimentsCount}
                    onChange={(e) =>
                      handleMaxExperimentsCountChange(e.target.value)
                    }
                    className={cn({
                      "border-destructive": Boolean(maxExperimentsError),
                    })}
                  />
                </FormControl>
                <Description>
                  Limit how many experiments are loaded (max{" "}
                  {MAX_MAX_EXPERIMENTS}).
                </Description>
                {maxExperimentsError && (
                  <FormMessage>{maxExperimentsError}</FormMessage>
                )}
              </FormItem>
            </>
          )}
      </div>
    </div>
  );
};

export default DashboardDataSourceSection;
