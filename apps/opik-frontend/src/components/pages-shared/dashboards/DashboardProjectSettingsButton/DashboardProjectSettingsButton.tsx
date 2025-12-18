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
import {
  UNSET_PROJECT_OPTION,
  UNSET_PROJECT_VALUE,
} from "@/lib/dashboard/utils";

const NONE_VALUE = UNSET_PROJECT_VALUE;

const DashboardProjectSettingsButton: React.FC = () => {
  const config = useDashboardStore(selectConfig);
  const setConfig = useDashboardStore(selectSetConfig);

  const selectedProjectValue = config?.projectIds?.[0] || NONE_VALUE;
  const selectedExperimentIds = config?.experimentIds || [];

  const handleProjectChange = useCallback(
    (value: string) => {
      if (!config) return;

      const projectIds = value === NONE_VALUE ? [] : [value];
      setConfig({ ...config, projectIds });
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
              Set the default project and experiments for all widgets.
              Individual widgets can override these settings if needed.
            </Description>
          </div>
          <div>
            <h4 className="comet-body-s-accented mb-2">Project</h4>
            <ProjectsSelectBox
              value={selectedProjectValue}
              onValueChange={handleProjectChange}
              customOptions={UNSET_PROJECT_OPTION}
              minWidth={280}
            />
          </div>
          <div>
            <h4 className="comet-body-s-accented mb-2">Experiments</h4>
            <ExperimentsSelectBox
              value={selectedExperimentIds}
              onValueChange={handleExperimentsChange}
              multiselect
              minWidth={280}
            />
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default DashboardProjectSettingsButton;
