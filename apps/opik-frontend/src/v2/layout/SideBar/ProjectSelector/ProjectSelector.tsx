import React, { useCallback, useRef, useState } from "react";
import {
  ChevronDown,
  ChevronUp,
  MoreHorizontal,
  Pencil,
  Plus,
  Trash,
} from "lucide-react";
import { Link, useNavigate, useRouter } from "@tanstack/react-router";

import { cn } from "@/lib/utils";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { ListAction } from "@/ui/list-action";
import { SearchInput } from "@/shared/SearchInput/SearchInput";
import { setActiveProject } from "@/hooks/useActiveProjectInitializer";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { Spinner } from "@/ui/spinner";
import useProjectsList from "@/api/projects/useProjectsList";
import useProjectById from "@/api/projects/useProjectById";
import useProjectDeleteMutation from "@/api/projects/useProjectDeleteMutation";
import { usePermissions } from "@/contexts/PermissionsContext";
import { DEFAULT_PROJECT_NAME, Project } from "@/types/projects";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import AddEditProjectDialog from "@/v2/pages/ProjectsPage/AddEditProjectDialog";
import ProjectAvatar from "@/shared/ProjectIcon/ProjectAvatar";
import { resolveProjectSwitchTarget } from "./resolveProjectSwitchTarget";

interface ProjectSelectorProps {
  expanded?: boolean;
}

const ProjectSelector: React.FC<ProjectSelectorProps> = ({
  expanded = true,
}) => {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [openCreateDialog, setOpenCreateDialog] = useState(false);
  const activeProjectId = useActiveProjectId();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const router = useRouter();

  const {
    permissions: { canCreateProjects },
  } = usePermissions();

  const { data: activeProject, isPending: isProjectPending } = useProjectById(
    { projectId: activeProjectId! },
    { enabled: !!activeProjectId },
  );

  const isLoading = !!activeProjectId && isProjectPending;

  const { data: projectsData } = useProjectsList(
    {
      workspaceName,
      search,
      page: 1,
      size: 50,
    },
    { enabled: open },
  );

  const handleSelect = useCallback(
    (projectId: string) => {
      setOpen(false);
      setSearch("");
      const target = resolveProjectSwitchTarget(
        router.state.matches,
        router.state.location.search as Record<string, unknown>,
        workspaceName,
        projectId,
      );
      navigate(target);
    },
    [navigate, router, workspaceName],
  );

  const renderExpandedLoading = () => (
    <PopoverTrigger asChild>
      <button className="flex w-full items-center gap-1.5 px-1 py-0.5">
        <Spinner size="xs" />
        <span className="comet-body-s flex-1 text-left text-muted-slate">
          Loading…
        </span>
      </button>
    </PopoverTrigger>
  );

  const renderProjectLabel = () => (
    <PopoverTrigger asChild>
      <button
        className="flex items-center gap-0.5 text-light-slate"
        aria-label="Open project selector"
      >
        <span className="comet-body-xs-accented">Project</span>
        {open ? (
          <ChevronUp className="size-3.5" />
        ) : (
          <ChevronDown className="size-3.5" />
        )}
      </button>
    </PopoverTrigger>
  );

  const renderExpandedNoProject = () => (
    <PopoverTrigger asChild>
      <button
        className={cn(
          "flex w-full items-center gap-1.5 rounded-md px-1 py-0.5",
          open && "bg-primary-foreground",
        )}
      >
        <ProjectAvatar projectId={activeProjectId} size="lg" />
        <div className="flex min-w-0 flex-1 flex-col items-stretch">
          <div className="flex items-center gap-0.5">
            <span className="comet-body-xs-accented text-light-slate">
              Project
            </span>
            <span className="shrink-0 text-light-slate">
              {open ? (
                <ChevronUp className="size-3.5" />
              ) : (
                <ChevronDown className="size-3.5" />
              )}
            </span>
          </div>
          <span className="comet-body-s-accented truncate text-left text-muted-slate">
            Select project
          </span>
        </div>
      </button>
    </PopoverTrigger>
  );

  const renderExpandedActiveProject = () => {
    if (!activeProject) return null;
    return (
      <div
        className={cn(
          "flex w-full items-center gap-1.5 rounded-md px-1 py-0.5",
          open && "bg-primary-foreground",
        )}
      >
        <Link
          to="/$workspaceName/projects/$projectId/home"
          params={{ workspaceName, projectId: activeProject.id }}
          className="shrink-0"
        >
          <ProjectAvatar projectId={activeProjectId} size="lg" />
        </Link>
        <div className="flex min-w-0 flex-1 flex-col items-stretch">
          {renderProjectLabel()}
          <Link
            to="/$workspaceName/projects/$projectId/home"
            params={{ workspaceName, projectId: activeProject.id }}
            className="w-full"
          >
            <TooltipWrapper content={activeProject.name}>
              <span className="comet-body-s-accented block w-full truncate text-left text-foreground hover:underline hover:underline-offset-4">
                {activeProject.name}
              </span>
            </TooltipWrapper>
          </Link>
        </div>
      </div>
    );
  };

  const renderExpandedTrigger = () => {
    if (isLoading) return renderExpandedLoading();
    if (!activeProject) return renderExpandedNoProject();
    return renderExpandedActiveProject();
  };

  const renderCollapsedIcon = () => {
    const iconContent = isLoading ? (
      <Spinner size="xs" />
    ) : (
      <ProjectAvatar projectId={activeProjectId} size="md" />
    );

    if (!activeProject) {
      return (
        <PopoverTrigger asChild>
          <button
            className={cn(
              "flex size-7 items-center justify-center rounded-md",
              open ? "bg-primary-foreground" : "hover:bg-primary-foreground",
            )}
          >
            {iconContent}
          </button>
        </PopoverTrigger>
      );
    }

    return (
      <TooltipWrapper content={activeProject.name} side="right">
        <Link
          to="/$workspaceName/projects/$projectId/home"
          params={{ workspaceName, projectId: activeProject.id }}
          className="flex size-7 items-center justify-center rounded-md hover:bg-primary-foreground"
        >
          {iconContent}
        </Link>
      </TooltipWrapper>
    );
  };

  const renderCollapsedTrigger = () => (
    <div className="relative w-fit self-center">
      {renderCollapsedIcon()}
      <TooltipWrapper content="Switch project" side="right">
        <PopoverTrigger asChild>
          <Button
            variant="outline"
            size="icon-4xs"
            aria-label="Open project selector"
            className="absolute -bottom-1 -right-1 text-foreground-secondary shadow-sm"
          >
            {open ? <ChevronUp /> : <ChevronDown />}
          </Button>
        </PopoverTrigger>
      </TooltipWrapper>
    </div>
  );

  return (
    <>
      <Popover open={open} onOpenChange={setOpen}>
        {expanded ? renderExpandedTrigger() : renderCollapsedTrigger()}
        <PopoverContent
          align="start"
          side="bottom"
          className="flex w-[280px] flex-col overflow-hidden p-1"
          sideOffset={4}
          style={{
            maxHeight: "var(--radix-popover-content-available-height)",
          }}
        >
          <DropdownMenuLabel size="sm">Projects</DropdownMenuLabel>
          <DropdownMenuSeparator className="my-1" />
          <div className="px-0.5" onKeyDown={(e) => e.stopPropagation()}>
            <SearchInput
              searchText={search}
              setSearchText={setSearch}
              variant="ghost"
              size="sm"
            />
          </div>
          <DropdownMenuSeparator className="my-1" />
          <div className="min-h-0 flex-1 overflow-auto">
            {projectsData?.content?.map((project) => (
              <ProjectItem
                key={project.id}
                project={project}
                isSelected={project.id === activeProjectId}
                workspaceName={workspaceName}
                onSelect={handleSelect}
                onDelete={
                  project.id === activeProjectId
                    ? () => {
                        setActiveProject(workspaceName, null);
                        navigate({
                          to: "/$workspaceName/projects",
                          params: { workspaceName },
                        });
                      }
                    : undefined
                }
              />
            ))}
          </div>
          {canCreateProjects && (
            <>
              <DropdownMenuSeparator className="my-1" />
              <ListAction
                variant="default"
                size="sm"
                onClick={() => {
                  setOpen(false);
                  setOpenCreateDialog(true);
                }}
              >
                <Plus className="size-3.5 shrink-0 text-light-slate" />
                <span>New project</span>
              </ListAction>
            </>
          )}
        </PopoverContent>
      </Popover>
      <AddEditProjectDialog
        open={openCreateDialog}
        setOpen={setOpenCreateDialog}
      />
    </>
  );
};

interface ProjectItemProps {
  project: Project;
  isSelected: boolean;
  workspaceName: string;
  onSelect: (projectId: string) => void;
  onDelete?: () => void;
}

const ProjectItem: React.FC<ProjectItemProps> = ({
  project,
  isSelected,
  workspaceName,
  onSelect,
  onDelete,
}) => {
  const resetKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean | number>(false);

  const {
    permissions: { canCreateProjects, canDeleteProjects },
  } = usePermissions();

  const { mutate: deleteProject } = useProjectDeleteMutation();

  const handleDelete = useCallback(() => {
    deleteProject({ projectId: project.id }, { onSuccess: () => onDelete?.() });
  }, [project.id, deleteProject, onDelete]);

  const isDefaultProject = project.name === DEFAULT_PROJECT_NAME;
  const canDelete = canDeleteProjects && !isDefaultProject;
  const hasActions = canCreateProjects || canDelete;

  return (
    <>
      <AddEditProjectDialog
        key={`edit-${resetKeyRef.current}`}
        project={project}
        open={openDialog === 2}
        setOpen={setOpenDialog}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={openDialog === 1}
        setOpen={setOpenDialog}
        onConfirm={handleDelete}
        title="Delete project"
        description="Deleting a project will also remove all the traces and their data. This action can't be undone. Are you sure you want to continue?"
        confirmText="Delete project"
        confirmButtonVariant="destructive"
      />
      <Link
        to="/$workspaceName/projects/$projectId/home"
        params={{ workspaceName, projectId: project.id }}
        className={cn(
          "comet-body-s group relative flex h-8 cursor-pointer items-center gap-2 rounded-md px-3 outline-none transition-colors hover:bg-primary-foreground hover:text-foreground",
          hasActions && "hover:pr-9",
          hasActions && isSelected && "pr-9",
          isSelected &&
            "bg-primary-100 text-primary hover:bg-secondary hover:text-primary",
        )}
        onClick={(e) => {
          if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
          e.preventDefault();
          onSelect(project.id);
        }}
      >
        <ProjectAvatar projectId={project.id} />
        <TooltipWrapper content={project.name}>
          <span
            className={cn(
              "comet-body-s flex-1 truncate",
              isSelected ? "text-primary" : "text-foreground",
            )}
          >
            {project.name}
          </span>
        </TooltipWrapper>
        {hasActions && (
          <DropdownMenu>
            <DropdownMenuTrigger asChild onClick={(e) => e.stopPropagation()}>
              <Button
                variant="minimal"
                size="icon-2xs"
                className={cn(
                  "absolute right-3 top-1/2 -translate-y-1/2 rounded pl-2 text-light-slate hover:text-foreground",
                  isSelected ? "visible" : "invisible group-hover:visible",
                )}
              >
                <MoreHorizontal className="size-3.5" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-40">
              {canCreateProjects && (
                <DropdownMenuItem
                  size="sm"
                  onClick={(e) => {
                    e.stopPropagation();
                    setOpenDialog(2);
                    resetKeyRef.current += 1;
                  }}
                >
                  <Pencil className="mr-1.5 size-3.5 text-light-slate" />
                  Edit
                </DropdownMenuItem>
              )}
              {canCreateProjects && canDelete && <DropdownMenuSeparator />}
              {canDelete && (
                <DropdownMenuItem
                  variant="destructive"
                  size="sm"
                  onClick={(e) => {
                    e.stopPropagation();
                    setOpenDialog(1);
                    resetKeyRef.current += 1;
                  }}
                >
                  <Trash className="mr-1.5 size-3.5" />
                  Delete
                </DropdownMenuItem>
              )}
            </DropdownMenuContent>
          </DropdownMenu>
        )}
      </Link>
    </>
  );
};

export default ProjectSelector;
