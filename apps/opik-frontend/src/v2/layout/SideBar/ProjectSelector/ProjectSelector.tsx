import React, { useCallback, useRef, useState } from "react";
import {
  Check,
  ChevronDown,
  MoreHorizontal,
  Pencil,
  Trash,
} from "lucide-react";
import { useNavigate } from "@tanstack/react-router";

import { cn } from "@/lib/utils";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { Separator } from "@/ui/separator";
import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { SearchInput } from "@/shared/SearchInput/SearchInput";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import useActiveProject from "@/hooks/useActiveProject";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import useProjectsList from "@/api/projects/useProjectsList";
import useProjectById from "@/api/projects/useProjectById";
import useProjectDeleteMutation from "@/api/projects/useProjectDeleteMutation";
import { usePermissions } from "@/contexts/PermissionsContext";
import { Project } from "@/types/projects";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import AddEditProjectDialog from "@/v2/pages/ProjectsPage/AddEditProjectDialog";

interface ProjectSelectorProps {
  expanded: boolean;
}

const ProjectSelector: React.FC<ProjectSelectorProps> = ({ expanded }) => {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const { setActiveProjectId } = useActiveProject();
  const activeProjectId = useActiveProjectId();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();

  const { data: activeProject } = useProjectById(
    { projectId: activeProjectId! },
    { enabled: !!activeProjectId },
  );

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
      setActiveProjectId(projectId);
      setOpen(false);
      setSearch("");
      navigate({
        to: "/$workspaceName/projects/$projectId/traces",
        params: { workspaceName, projectId },
      });
    },
    [setActiveProjectId, navigate, workspaceName],
  );

  const projectName = activeProject?.name ?? "Select project";

  const trigger = expanded ? (
    <PopoverTrigger asChild>
      <button className="flex w-full items-center gap-1 rounded-md bg-muted px-2 py-1">
        <span className="comet-body-s-accented flex-1 truncate text-left text-foreground">
          {projectName}
        </span>
        <ChevronDown className="size-3.5 shrink-0 text-muted-slate" />
      </button>
    </PopoverTrigger>
  ) : (
    <TooltipWrapper content={projectName} side="right">
      <PopoverTrigger asChild>
        <button className="flex w-9 items-center justify-center rounded-md bg-muted py-1">
          <ChevronDown className="size-3.5 text-muted-slate" />
        </button>
      </PopoverTrigger>
    </TooltipWrapper>
  );

  return (
    <Popover open={open} onOpenChange={setOpen}>
      {trigger}
      <PopoverContent
        align="start"
        side="bottom"
        className="w-[216px] p-1"
        sideOffset={4}
      >
        <div className="px-3 py-2">
          <span className="comet-body-s-accented text-foreground">
            Projects
          </span>
        </div>
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
              isSelected={project.id === activeProjectId}
              onSelect={handleSelect}
            />
          ))}
        </div>
        <Separator className="my-1" />
        <Button
          variant="ghost"
          size="sm"
          className="w-full justify-start text-primary"
          onClick={() => {
            setOpen(false);
            navigate({
              to: "/$workspaceName/projects",
              params: { workspaceName },
            });
          }}
        >
          Manage projects
        </Button>
      </PopoverContent>
    </Popover>
  );
};

interface ProjectItemProps {
  project: Project;
  isSelected: boolean;
  onSelect: (projectId: string) => void;
}

const ProjectItem: React.FC<ProjectItemProps> = ({
  project,
  isSelected,
  onSelect,
}) => {
  const resetKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean | number>(false);

  const {
    permissions: { canCreateProjects, canDeleteProjects },
  } = usePermissions();

  const { mutate: deleteProject } = useProjectDeleteMutation();

  const handleDelete = useCallback(() => {
    deleteProject({ projectId: project.id });
  }, [project.id, deleteProject]);

  const hasActions = canCreateProjects || canDeleteProjects;

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
      <div
        className={cn(
          "group flex cursor-pointer items-center gap-2 rounded-md px-3 py-1.5 hover:bg-primary-foreground",
          isSelected && "bg-muted",
        )}
        onClick={() => onSelect(project.id)}
      >
        <Check
          className={cn(
            "size-3.5 shrink-0",
            isSelected ? "text-foreground" : "invisible",
          )}
        />
        <span className="comet-body-s flex-1 truncate text-foreground">
          {project.name}
        </span>
        {hasActions && (
          <DropdownMenu>
            <DropdownMenuTrigger asChild onClick={(e) => e.stopPropagation()}>
              <Button
                variant="minimal"
                size="icon-2xs"
                className="invisible shrink-0 group-hover:visible"
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
              {canDeleteProjects && (
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
      </div>
    </>
  );
};

export default ProjectSelector;
