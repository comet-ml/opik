import React from "react";
import useLocalStorageState from "use-local-storage-state";

import { DropdownOption } from "@/types/shared";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import ProjectSelector from "@/components/pages/HomePage/ProjectSelector";

enum PERIOD_OPTION_TYPE {
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

const TIME_PERIOD_KEY = "home-time-period";
const SELECTED_PROJECTS_KEY = "home-selected-projects";

type OverallPerformanceActionsPanelProps = {
  period?: string;
  setPeriod?: (period: string) => void;
};

const OverallPerformanceActionsPanel: React.FC<
  OverallPerformanceActionsPanelProps
> = () => {
  const [period, setPeriod] = useLocalStorageState<PERIOD_OPTION_TYPE>(
    TIME_PERIOD_KEY,
    {
      defaultValue: PERIOD_OPTION_TYPE.THREE_DAYS,
    },
  );

  const [projectsIds, setProjectsIds] = useLocalStorageState<string[]>(
    SELECTED_PROJECTS_KEY,
    {
      defaultValue: [],
    },
  );

  // TODO lala need to sync about validation of projects ids (What about saving per workspace???)

  return (
    <div className="flex items-center justify-between gap-4 pt-4">
      <ProjectSelector
        projectIds={projectsIds}
        setProjectIds={setProjectsIds}
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
