import React, { useCallback, useRef, useState } from "react";
import {
  Check,
  ChevronDown,
  ChevronUp,
  MoreHorizontal,
  Pencil,
  Trash,
} from "lucide-react";
import { Link, useNavigate } from "@tanstack/react-router";

import { cn } from "@/lib/utils";
import {
  Popover,
  PopoverAnchor,
  PopoverContent,
  PopoverTrigger,
} from "@/ui/popover";
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
  const activeProjectId = useActiveProjectId();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();

  const { data: activeProject, isPending: isProjectPending } = useProjectById(
    { projectId: activeProjectId! },
    { enabled: !!activeProjectId },
  );

  const isLoading = !!activeProjectId && isProjectPending;

  const iconIndices = useProjectIconIndices();

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

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverAnchor asChild>
        <PopoverTrigger asChild>
          {expanded ? (
            <button
              className={cn(
                "flex w-full items-center gap-2 rounded-md px-2 py-1.5",
                open && "bg-primary-foreground",
              )}
            >
              {isLoading ? (
                <>
                  <Spinner size="xs" />
                  <span className="comet-body-s flex-1 text-left text-muted-slate">
                    Loading…
                  </span>
                </>
              ) : (
                <>
                  <ProjectIcon
                    index={
                      activeProjectId
                        ? iconIndices.get(activeProjectId) ?? 0
                        : 0
                    }
                    variant="owl"
                  />
                  <div className="flex min-w-0 flex-1 flex-col items-start">
                    <div className="flex w-full items-center gap-0.5">
                      <span className="comet-body-s text-light-slate">
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
                    {activeProject ? (
                      <TooltipWrapper content={activeProject.name}>
                        <span className="comet-body-s-accented w-full truncate text-left text-foreground hover:underline hover:underline-offset-4">
                          {activeProject.name}
                        </span>
                      </TooltipWrapper>
                    ) : (
                      <span className="comet-body-s-accented truncate text-left text-muted-slate">
                        Select project
                      </span>
                    )}
                  </div>
                </>
              )}
            </button>
          ) : (
            <button
              className={cn(
                "flex size-7 items-center justify-center rounded-md py-1",
                open ? "bg-primary-foreground" : "hover:bg-primary-foreground",
              )}
            >
              {isLoading ? (
                <Spinner size="xs" />
              ) : open ? (
                <ChevronUp className="size-3.5 text-foreground" />
              ) : (
                <ChevronDown className="size-3.5 text-foreground" />
              )}
            </button>
          )}
        </PopoverTrigger>
      </PopoverAnchor>
      <PopoverContent
        align="start"
        side="bottom"
        className="w-[320px] p-1"
        sideOffset={4}
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
        <div className="max-h-[300px] overflow-auto">
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
      </PopoverContent>
    </Popover>
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
