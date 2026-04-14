import React, { useCallback, useRef, useState } from "react";
import {
  Check,
  ChevronDown,
  ChevronUp,
  MoreHorizontal,
  Pencil,
  Plus,
  Trash,
} from "lucide-react";
import { Link, useMatchRoute, useNavigate } from "@tanstack/react-router";

import { cn } from "@/lib/utils";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { Separator } from "@/ui/separator";
import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
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
import ProjectIcon from "@/shared/ProjectIcon/ProjectIcon";
import useProjectIconIndices from "@/hooks/useProjectIconIndex";

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
  const matchRoute = useMatchRoute();
  const isOnProjectHome =
    !!activeProjectId &&
    !!matchRoute({
      to: "/$workspaceName/projects/$projectId/home",
      params: { workspaceName, projectId: activeProjectId },
    });

  const {
    permissions: { canCreateProjects },
  } = usePermissions();

  const { data: activeProject, isPending: isProjectPending } = useProjectById(
    { projectId: activeProjectId! },
    { enabled: !!activeProjectId },
  );

  const isLoading = !!activeProjectId && isProjectPending;

  const iconIndices = useProjectIconIndices();
  const activeIconIndex = activeProjectId
    ? iconIndices.get(activeProjectId) ?? 0
    : 0;

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
      navigate({
        to: "/$workspaceName/projects/$projectId/home",
        params: { workspaceName, projectId },
      });
    },
    [navigate, workspaceName],
  );

  const renderExpandedLoading = () => (
    <PopoverTrigger asChild>
      <button className="flex w-full items-center gap-2 rounded-md px-2 py-1.5">
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
        <span className="comet-body-s">Project</span>
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
          "flex w-full items-center gap-2 rounded-md px-2 py-1.5",
          open && "bg-primary-foreground",
        )}
      >
        <ProjectIcon index={activeIconIndex} size="lg" />
        <div className="flex min-w-0 flex-1 flex-col items-start">
          <div className="flex w-full items-center gap-0.5">
            <span className="comet-body-s text-light-slate">Project</span>
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
          "flex w-full items-center gap-2 rounded-md px-2 py-1.5",
          open && "bg-primary-foreground",
        )}
      >
        <Link
          to="/$workspaceName/projects/$projectId/home"
          params={{ workspaceName, projectId: activeProject.id }}
          className="shrink-0"
        >
          <ProjectIcon index={activeIconIndex} size="lg" />
        </Link>
        <div className="flex min-w-0 flex-1 flex-col items-start">
          {renderProjectLabel()}
          <Link
            to="/$workspaceName/projects/$projectId/home"
            params={{ workspaceName, projectId: activeProject.id }}
            className="w-full"
          >
            <TooltipWrapper content={activeProject.name}>
              <span
                className={cn(
                  "comet-body-s-accented block w-full truncate text-left hover:underline hover:underline-offset-4",
                  isOnProjectHome
                    ? "text-primary underline underline-offset-4"
                    : "text-foreground",
                )}
              >
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
      <ProjectIcon index={activeIconIndex} size="md" />
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
          className={cn(
            "flex size-7 items-center justify-center rounded-md",
            isOnProjectHome
              ? "bg-primary-100 hover:bg-primary-200"
              : "hover:bg-primary-foreground",
          )}
        >
          {iconContent}
        </Link>
      </TooltipWrapper>
    );
  };

  const renderCollapsedTrigger = () => (
    <div className="relative w-fit self-center">
      {renderCollapsedIcon()}
      <PopoverTrigger asChild>
        <Button
          variant="outline"
          size="icon-2xs"
          aria-label="Open project selector"
          className="absolute -bottom-1 -left-1 size-3.5 rounded bg-background text-light-slate shadow-sm hover:text-foreground [&>svg]:size-3.5"
        >
          {open ? <ChevronUp /> : <ChevronDown />}
        </Button>
      </PopoverTrigger>
    </div>
  );

  return (
    <>
      <Popover open={open} onOpenChange={setOpen}>
        {expanded ? renderExpandedTrigger() : renderCollapsedTrigger()}
        <PopoverContent
          align="start"
          side="bottom"
          className="flex w-[320px] flex-col overflow-hidden p-1"
          sideOffset={4}
          style={{
            maxHeight: "var(--radix-popover-content-available-height)",
          }}
        >
          <div className="px-3 py-2">
            <span className="comet-body-s-accented text-foreground">
              Projects
            </span>
          </div>
          <Separator className="my-1" />
          <div className="px-1">
            <SearchInput
              searchText={search}
              setSearchText={setSearch}
              variant="ghost"
              size="sm"
            />
          </div>
          <Separator className="my-1" />
          <div className="min-h-0 flex-1 overflow-auto">
            {projectsData?.content?.map((project) => (
              <ProjectItem
                key={project.id}
                project={project}
                iconIndex={iconIndices.get(project.id) ?? 0}
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
              <Separator className="my-1" />
              <button
                className="flex w-full items-center gap-2 rounded-md px-3 py-2 hover:bg-primary-foreground"
                onClick={() => {
                  setOpen(false);
                  setOpenCreateDialog(true);
                }}
              >
                <Plus className="size-3.5 shrink-0 text-foreground" />
                <span className="comet-body-s text-foreground">
                  New project
                </span>
              </button>
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
  iconIndex: number;
  isSelected: boolean;
  workspaceName: string;
  onSelect: (projectId: string) => void;
  onDelete?: () => void;
}

const ProjectItem: React.FC<ProjectItemProps> = ({
  project,
  iconIndex,
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
          "group relative flex h-8 items-center gap-2 rounded-md px-3 hover:bg-primary-foreground hover:pr-9",
          isSelected && "bg-primary-100 text-primary",
        )}
        onClick={(e) => {
          if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
          e.preventDefault();
          onSelect(project.id);
        }}
      >
        <ProjectIcon index={iconIndex} />
        <TooltipWrapper content={project.name}>
          <span className="comet-body-s flex-1 truncate text-foreground">
            {project.name}
          </span>
        </TooltipWrapper>
        {isSelected && <Check className="size-3.5 shrink-0 text-primary" />}
        {hasActions && (
          <DropdownMenu>
            <DropdownMenuTrigger asChild onClick={(e) => e.stopPropagation()}>
              <Button
                variant="minimal"
                size="icon-2xs"
                className="invisible absolute right-3 top-1/2 -translate-y-1/2 rounded pl-2 group-hover:visible"
              >
                <MoreHorizontal className="size-3.5" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-40">
              {canCreateProjects && (
                <DropdownMenuItem
                  onClick={(e) => {
                    e.stopPropagation();
                    setOpenDialog(2);
                    resetKeyRef.current += 1;
                  }}
                >
                  <Pencil className="mr-2 size-3.5" />
                  Edit
                </DropdownMenuItem>
              )}
              {canCreateProjects && canDelete && <DropdownMenuSeparator />}
              {canDelete && (
                <DropdownMenuItem
                  variant="destructive"
                  onClick={(e) => {
                    e.stopPropagation();
                    setOpenDialog(1);
                    resetKeyRef.current += 1;
                  }}
                >
                  <Trash className="mr-2 size-3.5" />
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
