import React, { useCallback, useMemo, useState } from "react";
import { ArrowUpRight, Plus } from "lucide-react";
import { useNavigate, useRouter } from "@tanstack/react-router";

import { Button } from "@/ui/button";
import { DropdownMenuLabel, DropdownMenuSeparator } from "@/ui/dropdown-menu";
import { ListAction } from "@/ui/list-action";
import { SearchInput } from "@/shared/SearchInput/SearchInput";
import { setActiveProject } from "@/hooks/useActiveProjectInitializer";
import useAppStore from "@/store/AppStore";
import useProjectsList from "@/api/projects/useProjectsList";
import { usePermissions } from "@/contexts/PermissionsContext";
import { Project } from "@/types/projects";
import ProjectMenuItem from "./ProjectMenuItem";
import usePinnedProjects, { PinnedProject } from "./usePinnedProjects";
import { resolveProjectSwitchTarget } from "./resolveProjectSwitchTarget";

const RECENTLY_UPDATED_SIZE = 10;
const SEARCH_RESULTS_SIZE = 25;

const RECENTLY_UPDATED_SORTING = [{ id: "last_updated_trace_at", desc: true }];

const synthesizeProject = (pinned: PinnedProject): Project => ({
  id: pinned.id,
  name: pinned.name,
  description: "",
  created_at: "",
  created_by: "",
  last_updated_at: "",
  last_updated_by: "",
});

interface ProjectMenuContentProps {
  activeProjectId: string | null;
  onClose: () => void;
  onRequestCreateProject: () => void;
}

const ProjectMenuContent: React.FC<ProjectMenuContentProps> = ({
  activeProjectId,
  onClose,
  onRequestCreateProject,
}) => {
  const [search, setSearch] = useState("");
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const router = useRouter();

  const {
    permissions: { canCreateProjects },
  } = usePermissions();

  const { pinnedProjects, isPinned, pinProject, unpinProject } =
    usePinnedProjects(workspaceName);

  const isSearching = search.trim().length > 0;

  const { data: projectsData } = useProjectsList({
    workspaceName,
    search: isSearching ? search : undefined,
    sorting: isSearching ? undefined : RECENTLY_UPDATED_SORTING,
    page: 1,
    size: isSearching ? SEARCH_RESULTS_SIZE : RECENTLY_UPDATED_SIZE,
  });

  const projects = useMemo(() => projectsData?.content ?? [], [projectsData]);

  const projectsById = useMemo(
    () => new Map(projects.map((project) => [project.id, project])),
    [projects],
  );

  const handleSelect = useCallback(
    (projectId: string) => {
      onClose();
      const target = resolveProjectSwitchTarget(
        router.state.matches,
        router.state.location.search as Record<string, unknown>,
        workspaceName,
        projectId,
      );
      navigate(target);
    },
    [navigate, onClose, router, workspaceName],
  );

  const handleTogglePin = useCallback(
    (project: Project, pinned: boolean) => {
      if (pinned) {
        pinProject(project);
      } else {
        unpinProject(project.id);
      }
    },
    [pinProject, unpinProject],
  );

  const handleProjectDeleted = useCallback(
    (projectId: string) => {
      unpinProject(projectId);
      if (projectId === activeProjectId) {
        setActiveProject(workspaceName, null);
        onClose();
        navigate({
          to: "/$workspaceName/projects",
          params: { workspaceName },
        });
      }
    },
    [unpinProject, activeProjectId, navigate, onClose, workspaceName],
  );

  const handleViewAll = useCallback(() => {
    onClose();
    navigate({
      to: "/$workspaceName/projects",
      params: { workspaceName },
    });
  }, [navigate, onClose, workspaceName]);

  const renderItem = (project: Project, fullDataAvailable: boolean) => (
    <ProjectMenuItem
      key={project.id}
      project={project}
      isSelected={project.id === activeProjectId}
      isPinned={isPinned(project.id)}
      fullDataAvailable={fullDataAvailable}
      workspaceName={workspaceName}
      onSelect={handleSelect}
      onTogglePin={handleTogglePin}
      onDeleted={() => handleProjectDeleted(project.id)}
    />
  );

  const renderSections = () => {
    if (isSearching) {
      return (
        <div className="min-h-0 flex-1 overflow-auto">
          {projects.map((project) => renderItem(project, true))}
        </div>
      );
    }

    const recentlyUpdated = projects.filter((project) => !isPinned(project.id));

    return (
      <div className="min-h-0 flex-1 overflow-auto">
        {pinnedProjects.length > 0 && (
          <>
            <DropdownMenuLabel size="sm" className="text-light-slate">
              Pinned
            </DropdownMenuLabel>
            {pinnedProjects.map((pinned) => {
              const full = projectsById.get(pinned.id);
              return renderItem(
                full ?? synthesizeProject(pinned),
                Boolean(full),
              );
            })}
            <DropdownMenuSeparator className="my-1" />
          </>
        )}
        <DropdownMenuLabel size="sm" className="text-light-slate">
          Recently updated
        </DropdownMenuLabel>
        {recentlyUpdated.map((project) => renderItem(project, true))}
      </div>
    );
  };

  return (
    <>
      <div className="flex items-center justify-between pl-2 pr-1">
        <DropdownMenuLabel size="sm" className="px-0">
          Projects
        </DropdownMenuLabel>
        {canCreateProjects && (
          <Button variant="ghost" size="2xs" onClick={onRequestCreateProject}>
            <Plus className="mr-1 size-3.5" />
            New
          </Button>
        )}
      </div>
      <DropdownMenuSeparator className="my-1" />
      <div className="px-0.5" onKeyDown={(e) => e.stopPropagation()}>
        <SearchInput
          searchText={search}
          setSearchText={setSearch}
          placeholder="Search project"
          variant="ghost"
          dimension="sm"
        />
      </div>
      <DropdownMenuSeparator className="my-1" />
      {renderSections()}
      <DropdownMenuSeparator className="my-1" />
      <ListAction variant="default" size="sm" onClick={handleViewAll}>
        <span>View all projects</span>
        <ArrowUpRight className="size-3.5 shrink-0 text-light-slate" />
      </ListAction>
    </>
  );
};

export default ProjectMenuContent;
