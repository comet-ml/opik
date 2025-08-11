import React from "react";

import { Project } from "@/types/projects";
import ProjectSelector from "@/components/pages/HomePage/ProjectSelector";

type OverallPerformanceActionsPanelProps = {
  projectsIds: string[];
  setProjectsIds: (projectsIds: string[]) => void;
  projects: Project[];
  totalProjects: number;
  rightSection: React.ReactNode;
};

const OverallPerformanceActionsPanel: React.FC<
  OverallPerformanceActionsPanelProps
> = ({
  projectsIds,
  setProjectsIds,
  projects,
  totalProjects,
  rightSection,
}) => {
  return (
    <div className="flex items-center justify-between gap-4 pt-3">
      <ProjectSelector
        projectIds={projectsIds}
        setProjectIds={setProjectsIds}
        projects={projects}
        totalProjects={totalProjects}
      ></ProjectSelector>
      <div className="shrink-0">{rightSection}</div>
    </div>
  );
};

export default OverallPerformanceActionsPanel;
