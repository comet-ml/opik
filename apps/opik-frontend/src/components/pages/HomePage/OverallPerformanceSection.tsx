import React, { useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import dayjs from "dayjs";

import OverallPerformanceActionsPanel, {
  PERIOD_OPTION_TYPE,
} from "@/components/pages/HomePage/OverallPerformanceActionsPanel";
import MetricsOverview from "@/components/pages/HomePage/MetricsOverview";
import useLocalStorageState from "use-local-storage-state";
import useProjectsList from "@/api/projects/useProjectsList";
import useAppStore from "@/store/AppStore";
import { LOADED_PROJECTS_COUNT } from "@/components/pages/HomePage/ProjectSelector";

const nowUTC = dayjs().utc();

const TIME_PERIOD_KEY = "home-time-period";
const SELECTED_PROJECTS_KEY = "home-selected-projects";

const OverallPerformanceSection = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [period, setPeriod] = useLocalStorageState<PERIOD_OPTION_TYPE>(
    TIME_PERIOD_KEY,
    {
      defaultValue: PERIOD_OPTION_TYPE.FOURTEEN_DAYS,
    },
  );

  const [projectsIds, setProjectsIds] = useLocalStorageState<string[]>(
    SELECTED_PROJECTS_KEY,
    {
      defaultValue: [],
    },
  );

  const { data: projectData, isPending } = useProjectsList(
    {
      workspaceName,
      sorting: [
        {
          desc: true,
          id: "last_updated_trace_at",
        },
      ],
      page: 1,
      size: LOADED_PROJECTS_COUNT,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const projects = useMemo(() => projectData?.content || [], [projectData]);

  const selectedProjects = useMemo(() => {
    if (!projectsIds || projectsIds.length === 0) {
      return [];
    }
    return projects.filter((p) => projectsIds.includes(p.id));
  }, [projectsIds, projects]);

  const { intervalStart, intervalEnd } = useMemo(() => {
    return {
      intervalStart: nowUTC
        .subtract(Number(period), "days")
        .startOf("day")
        .format(),
      intervalEnd: nowUTC.format(),
    };
  }, [period]);

  return (
    <div className="py-6">
      <div className="sticky top-0 z-10 bg-soft-background pb-3 pt-2">
        <h2 className="comet-title-m truncate break-words">
          Overall performance
        </h2>
        <OverallPerformanceActionsPanel
          period={period}
          setPeriod={setPeriod}
          projectsIds={projectsIds}
          setProjectsIds={setProjectsIds}
          projects={projects}
          totalProjects={projectData?.total || 0}
        />
      </div>
      <MetricsOverview
        projects={selectedProjects}
        projectsPending={isPending}
        intervalStart={intervalStart}
        intervalEnd={intervalEnd}
      />
    </div>
  );
};

export default OverallPerformanceSection;
