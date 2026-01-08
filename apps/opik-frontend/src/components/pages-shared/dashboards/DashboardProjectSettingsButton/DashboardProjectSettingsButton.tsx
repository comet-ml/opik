import React, { useCallback } from "react";
import { Settings } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Description } from "@/components/ui/description";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import ExperimentsSelectBox from "@/components/pages-shared/experiments/ExperimentsSelectBox/ExperimentsSelectBox";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import {
  useDashboardStore,
  selectConfig,
  selectSetConfig,
} from "@/store/DashboardStore";

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
              Select a default project to visualize data for this dashboard.
              Individual widgets can override these settings if needed.
            </Description>
          </div>
          <div>
            <h4 className="comet-body-s-accented mb-2">Default project</h4>
            {renderProjectSelector()}
            <Description className="mt-1">
              Choose the project this dashboard visualizes data from by default.
            </Description>
          </div>
          <div>
            <h4 className="comet-body-s-accented mb-2">Default experiments</h4>
            {renderExperimentsSelector()}
            <Description className="mt-1">
              Select which experiments are shown by default in widgets that use
              experiment data.
            </Description>
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default DashboardProjectSettingsButton;
