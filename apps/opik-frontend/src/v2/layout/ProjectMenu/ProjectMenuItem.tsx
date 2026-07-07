import React, { useCallback, useRef, useState } from "react";
import { MoreHorizontal, Pencil, Pin, Trash } from "lucide-react";
import { Link } from "@tanstack/react-router";

import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { usePermissions } from "@/contexts/PermissionsContext";
import { DEFAULT_PROJECT_NAME, Project } from "@/types/projects";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import AddEditProjectDialog from "@/v2/pages/ProjectsPage/AddEditProjectDialog";
import ProjectAvatar from "@/shared/ProjectIcon/ProjectAvatar";
import useProjectDeleteMutation from "@/api/projects/useProjectDeleteMutation";

interface ProjectMenuItemProps {
  project: Project;
  isSelected: boolean;
  isPinned: boolean;
  fullDataAvailable: boolean;
  workspaceName: string;
  onSelect: (projectId: string) => void;
  onTogglePin: (project: Project, pinned: boolean) => void;
  onDeleted?: () => void;
}

const ProjectMenuItem: React.FC<ProjectMenuItemProps> = ({
  project,
  isSelected,
  isPinned,
  fullDataAvailable,
  workspaceName,
  onSelect,
  onTogglePin,
  onDeleted,
}) => {
  const resetKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean | number>(false);

  const {
    permissions: { canCreateProjects, canDeleteProjects },
  } = usePermissions();

  const { mutate: deleteProject } = useProjectDeleteMutation();

  const handleDelete = useCallback(() => {
    deleteProject(
      { projectId: project.id },
      { onSuccess: () => onDeleted?.() },
    );
  }, [project.id, deleteProject, onDeleted]);

  const isDefaultProject = project.name === DEFAULT_PROJECT_NAME;
  const canDelete = canDeleteProjects && !isDefaultProject;
  const showActions = fullDataAvailable && (canCreateProjects || canDelete);

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
        <div className="flex shrink-0 items-center gap-0.5">
          {showActions && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild onClick={(e) => e.stopPropagation()}>
                <Button
                  variant="minimal"
                  size="icon-2xs"
                  className="invisible rounded text-light-slate hover:text-foreground group-hover:visible data-[state=open]:visible"
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
          <TooltipWrapper content={isPinned ? "Unpin project" : "Pin project"}>
            <Button
              variant="minimal"
              size="icon-2xs"
              aria-label={isPinned ? "Unpin project" : "Pin project"}
              className={cn(
                "rounded hover:text-foreground",
                isPinned
                  ? "visible text-foreground"
                  : "invisible text-light-slate group-hover:visible",
              )}
              onClick={(e) => {
                e.preventDefault();
                e.stopPropagation();
                onTogglePin(project, !isPinned);
              }}
            >
              <Pin className={cn("size-3.5", isPinned && "fill-current")} />
            </Button>
          </TooltipWrapper>
        </div>
      </Link>
    </>
  );
};

export default ProjectMenuItem;
