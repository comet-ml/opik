import React, { useCallback, useMemo } from "react";
import isArray from "lodash/isArray";
import isFunction from "lodash/isFunction";
import { Settings, Filter, ListChecks } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Description } from "@/components/ui/description";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import ExperimentsSelectBox from "@/components/pages-shared/experiments/ExperimentsSelectBox/ExperimentsSelectBox";
import FiltersAccordionSection from "@/components/shared/FiltersAccordionSection/FiltersAccordionSection";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import {
  useDashboardStore,
  selectConfig,
  selectSetConfig,
} from "@/store/DashboardStore";
import { EXPERIMENT_DATA_SOURCE } from "@/types/dashboard";
import { Filters } from "@/types/filters";
import {
  COLUMN_DATASET_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";

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

interface DashboardProjectSettingsButtonProps {
  disableProjectSelector?: boolean;
  disableExperimentsSelector?: boolean;
}

const DashboardProjectSettingsButton: React.FC<
  DashboardProjectSettingsButtonProps
> = ({
  disableProjectSelector = false,
  disableExperimentsSelector = false,
}) => {
  const config = useDashboardStore(selectConfig);
  const setConfig = useDashboardStore(selectSetConfig);

  const selectedProjectValue = config?.projectIds?.[0] || "";
  const selectedExperimentIds = config?.experimentIds || [];
  const experimentDataSource =
    config?.experimentDataSource || EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS;
  const experimentFilters = useMemo(
    () => (isArray(config?.experimentFilters) ? config.experimentFilters : []),
    [config?.experimentFilters],
  );
  const maxExperimentsCount = config?.maxExperimentsCount;

  const handleProjectChange = useCallback(
    (value: string) => {
      if (!config) return;

      setConfig({ ...config, projectIds: [value] });
    },
    [config, setConfig],
  );

  const handleExperimentsChange = useCallback(
    (value: string[]) => {
      if (!config) return;

      setConfig({ ...config, experimentIds: value });
    },
    [config, setConfig],
  );

  const handleDataSourceChange = useCallback(
    (value: EXPERIMENT_DATA_SOURCE) => {
      if (!config) return;
      setConfig({ ...config, experimentDataSource: value });
    },
    [config, setConfig],
  );

  const handleFiltersChange = useCallback(
    (filtersOrUpdater: Filters | ((prev: Filters) => Filters)) => {
      if (!config) return;
      const updatedFilters = isFunction(filtersOrUpdater)
        ? filtersOrUpdater(experimentFilters)
        : filtersOrUpdater;
      setConfig({ ...config, experimentFilters: updatedFilters });
    },
    [config, setConfig, experimentFilters],
  );

  const handleMaxExperimentsCountChange = useCallback(
    (value: number | undefined) => {
      if (!config) return;
      setConfig({ ...config, maxExperimentsCount: value });
    },
    [config, setConfig],
  );

  const renderProjectSelector = () => {
    const selectBox = (
      <ProjectsSelectBox
        value={selectedProjectValue}
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
        value={selectedExperimentIds}
        onValueChange={handleExperimentsChange}
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
    <Popover>
      <TooltipWrapper content="Dashboard defaults">
        <PopoverTrigger asChild>
          <Button size="icon-sm" variant="outline">
            <Settings className="size-3.5" />
          </Button>
        </PopoverTrigger>
      </TooltipWrapper>
      <PopoverContent align="end" className="w-80">
        <div className="space-y-4">
          <div>
            <h3 className="comet-title-s mb-2">Dashboard defaults</h3>
            <Description>
              Select the defaults to visualize data for this dashboard.
              Individual widgets can override these settings if needed.
            </Description>
          </div>
          <div>
            <h4 className="comet-body-s-accented mb-2">Default project</h4>
            {renderProjectSelector()}
            <Description className="mt-1">
              Select the default project for widgets that show project data.
            </Description>
          </div>
          <div>
            <h4 className="comet-body-s-accented mb-2">
              Experiment data source
            </h4>
            <ToggleGroup
              type="single"
              variant="ghost"
              value={experimentDataSource}
              onValueChange={(value) => {
                if (value) {
                  handleDataSourceChange(value as EXPERIMENT_DATA_SOURCE);
                }
              }}
              className="mb-2 w-fit justify-start"
            >
              <ToggleGroupItem
                value={EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS}
                aria-label="Manual selection"
                className="gap-1.5"
              >
                <ListChecks className="size-3.5" />
                <span>Manual</span>
              </ToggleGroupItem>
              <ToggleGroupItem
                value={EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP}
                aria-label="Filter experiments"
                className="gap-1.5"
              >
                <Filter className="size-3.5" />
                <span>Filter</span>
              </ToggleGroupItem>
            </ToggleGroup>

            {experimentDataSource ===
              EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS && (
              <>
                {renderExperimentsSelector()}
                <Description className="mt-1">
                  Select the default experiments for widgets that show
                  experiment data.
                </Description>
              </>
            )}

            {experimentDataSource ===
              EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP && (
              <div className="space-y-3">
                <FiltersAccordionSection
                  filters={experimentFilters}
                  columns={EXPERIMENT_FILTER_COLUMNS as ColumnData<unknown>[]}
                  onChange={handleFiltersChange}
                />
                <div>
                  <Label className="comet-body-s mb-1.5 block">
                    Maximum experiments
                  </Label>
                  <Input
                    type="number"
                    min={1}
                    max={100}
                    value={maxExperimentsCount ?? ""}
                    onChange={(e) => {
                      const value = e.target.value;
                      const numValue = value ? parseInt(value, 10) : undefined;
                      handleMaxExperimentsCountChange(numValue);
                    }}
                  />
                  <Description className="mt-1">
                    Maximum experiments to display (1-100)
                  </Description>
                </div>
              </div>
            )}
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default DashboardProjectSettingsButton;
