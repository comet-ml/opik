import React, { useCallback, useMemo, useState } from "react";
import { ArrowUpRight, Plus } from "lucide-react";
import { useNavigate, useRouter } from "@tanstack/react-router";

import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import { ListAction } from "@/ui/list-action";
import { SearchInput } from "@/shared/SearchInput/SearchInput";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import AddEditProjectDialog from "@/v2/pages-shared/ProjectsPage/AddEditProjectDialog";
import { setActiveProject } from "@/hooks/useActiveProjectInitializer";
import useAppStore from "@/store/AppStore";
import useProjectsList from "@/api/projects/useProjectsList";
import useProjectDeleteMutation from "@/api/projects/useProjectDeleteMutation";
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

const MenuSectionLabel: React.FC<
  React.PropsWithChildren<{ className?: string }>
> = ({ className, children }) => (
  <div
    className={cn(
      "comet-body-s-accented flex h-8 items-center px-3",
      className,
    )}
  >
    {children}
  </div>
);

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
  const [editTarget, setEditTarget] = useState<Project | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<Project | null>(null);
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const router = useRouter();

  const {
    permissions: { canCreateProjects },
  } = usePermissions();

  const { pinnedProjects, isPinned, pinProject, unpinProject } =
    usePinnedProjects(workspaceName);

  const { mutate: deleteProject } = useProjectDeleteMutation();

  const isSearching = search.trim().length > 0;

  const { data: projectsData } = useProjectsList({
    workspaceName,
    search: isSearching ? search : undefined,
    sorting: isSearching ? undefined : RECENTLY_UPDATED_SORTING,
    page: 1,
    size: isSearching
      ? SEARCH_RESULTS_SIZE
      : RECENTLY_UPDATED_SIZE + pinnedProjects.length,
  });

  const projects = useMemo(() => projectsData?.content ?? [], [projectsData]);

  const projectsById = useMemo(
    () => new Map(projects.map((project) => [project.id, project])),
    [projects],
  );

  const resolveTarget = useCallback(
    (projectId: string) =>
      resolveProjectSwitchTarget(
        router.state.matches,
        router.state.location.search as Record<string, unknown>,
        workspaceName,
        projectId,
      ),
    [router, workspaceName],
  );

  const handleSelect = useCallback(
    (projectId: string) => {
      onClose();
      navigate(resolveTarget(projectId));
    },
    [navigate, onClose, resolveTarget],
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

  const handleConfirmDelete = useCallback(() => {
    if (!deleteTarget) return;
    const projectId = deleteTarget.id;
    deleteProject(
      { projectId },
      { onSuccess: () => handleProjectDeleted(projectId) },
    );
  }, [deleteTarget, deleteProject, handleProjectDeleted]);

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
      linkTarget={resolveTarget(project.id)}
      onSelect={handleSelect}
      onTogglePin={handleTogglePin}
      onRequestEdit={setEditTarget}
      onRequestDelete={setDeleteTarget}
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

    const recentlyUpdated = projects
      .filter((project) => !isPinned(project.id))
      .slice(0, RECENTLY_UPDATED_SIZE);

    return (
      <div className="min-h-0 flex-1 overflow-auto">
        {pinnedProjects.length > 0 && (
          <>
            <MenuSectionLabel className="text-light-slate">
              Pinned
            </MenuSectionLabel>
            {pinnedProjects.map((pinned) => {
              const full = projectsById.get(pinned.id);
              return renderItem(
                full ?? synthesizeProject(pinned),
                Boolean(full),
              );
            })}
            <Separator className="-mx-px my-1 bg-muted" />
          </>
        )}
        <MenuSectionLabel className="text-light-slate">
          Recently updated
        </MenuSectionLabel>
        {recentlyUpdated.map((project) => renderItem(project, true))}
      </div>
    );
  };

  return (
    <>
      <div className="flex items-center justify-between pl-2 pr-1">
        <MenuSectionLabel className="px-0">Projects</MenuSectionLabel>
        {canCreateProjects && (
          <Button variant="ghost" size="2xs" onClick={onRequestCreateProject}>
            <Plus className="mr-1 size-3.5" />
            New
          </Button>
        )}
      </div>
      <Separator className="-mx-px my-1 bg-muted" />
      <div className="px-0.5">
        <SearchInput
          searchText={search}
          setSearchText={setSearch}
          placeholder="Search project"
          variant="ghost"
          dimension="sm"
        />
      </div>
      <Separator className="-mx-px my-1 bg-muted" />
      {renderSections()}
      <Separator className="-mx-px my-1 bg-muted" />
      <ListAction variant="default" size="sm" onClick={handleViewAll}>
        <span>View all projects</span>
        <ArrowUpRight className="size-3.5 shrink-0 text-light-slate" />
      </ListAction>
      {editTarget && (
        <AddEditProjectDialog
          key={editTarget.id}
          project={editTarget}
          open
          setOpen={(open) => {
            if (!open) setEditTarget(null);
          }}
        />
      )}
      <ConfirmDialog
        open={Boolean(deleteTarget)}
        setOpen={(open) => {
          if (!open) setDeleteTarget(null);
        }}
        onConfirm={handleConfirmDelete}
        title="Delete project"
        description="Deleting a project will also remove all the traces and their data. This action can't be undone. Are you sure you want to continue?"
        confirmText="Delete project"
        confirmButtonVariant="destructive"
      />
    </>
  );
};

export default ProjectMenuContent;
