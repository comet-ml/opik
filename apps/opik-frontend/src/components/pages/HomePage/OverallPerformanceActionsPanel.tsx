import React from "react";

import { Project } from "@/types/projects";
import { DropdownOption } from "@/types/shared";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import ProjectSelector from "@/components/pages/HomePage/ProjectSelector";

export enum PERIOD_OPTION_TYPE {
  THREE_DAYS = "3",
  SEVEN_DAYS = "7",
  FOURTEEN_DAYS = "14",
  THIRTY_DAYS = "30",
}

const PERIOD_OPTIONS: DropdownOption<PERIOD_OPTION_TYPE>[] = [
  {
    value: PERIOD_OPTION_TYPE.THREE_DAYS,
    label: "3 days",
  },
  {
    value: PERIOD_OPTION_TYPE.SEVEN_DAYS,
    label: "7 days",
  },
  {
    value: PERIOD_OPTION_TYPE.FOURTEEN_DAYS,
    label: "14 days",
  },
  {
    value: PERIOD_OPTION_TYPE.THIRTY_DAYS,
    label: "30 days",
  },
];

type OverallPerformanceActionsPanelProps = {
  period: PERIOD_OPTION_TYPE;
  setPeriod: (period: PERIOD_OPTION_TYPE) => void;
  projectsIds: string[];
  setProjectsIds: (projectsIds: string[]) => void;
  projects: Project[];
  totalProjects: number;
};

const OverallPerformanceActionsPanel: React.FC<
  OverallPerformanceActionsPanelProps
> = ({
  period,
  setPeriod,
  projectsIds,
  setProjectsIds,
  projects,
  totalProjects,
}) => {
  return (
    <div className="flex items-center justify-between gap-4 pt-3">
      <ProjectSelector
        projectIds={projectsIds}
        setProjectIds={setProjectsIds}
        projects={projects}
        totalProjects={totalProjects}
      ></ProjectSelector>
      <div className="w-32 shrink-0">
        <SelectBox
          value={period}
          onChange={setPeriod}
          options={PERIOD_OPTIONS}
          className="h-8"
        />
      </div>
    </div>
  );
};

export default OverallPerformanceActionsPanel;
