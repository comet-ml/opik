import React, { useState } from "react";
import { MoreHorizontal, Pencil, Pin, PinOff, Trash } from "lucide-react";
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
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import ProjectAvatar from "@/shared/ProjectIcon/ProjectAvatar";
import { ProjectSwitchTarget } from "./resolveProjectSwitchTarget";

interface ProjectMenuItemProps {
  project: Project;
  isSelected: boolean;
  isPinned: boolean;
  fullDataAvailable: boolean;
  linkTarget: ProjectSwitchTarget;
  onSelect: (projectId: string) => void;
  onTogglePin: (project: Project, pinned: boolean) => void;
  onRequestEdit: (project: Project) => void;
  onRequestDelete: (project: Project) => void;
}

const ProjectMenuItem: React.FC<ProjectMenuItemProps> = ({
  project,
  isSelected,
  isPinned,
  fullDataAvailable,
  linkTarget,
  onSelect,
  onTogglePin,
  onRequestEdit,
  onRequestDelete,
}) => {
  const [isMoreMenuOpen, setIsMoreMenuOpen] = useState(false);

  const {
    permissions: { canCreateProjects, canDeleteProjects },
  } = usePermissions();

  const isDefaultProject = project.name === DEFAULT_PROJECT_NAME;
  const canDelete = canDeleteProjects && !isDefaultProject;
  const showActions = fullDataAvailable && (canCreateProjects || canDelete);

  return (
    <div
      className={cn(
        "group relative flex h-8 items-center gap-2 rounded-md px-3 transition-colors hover:bg-primary-foreground",
        isSelected && "bg-primary-100 hover:bg-secondary",
      )}
    >
      <Link
        to={linkTarget.to}
        params={linkTarget.params}
        search={linkTarget.search}
        className="flex min-w-0 flex-1 cursor-pointer items-center gap-2 outline-none"
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
      </Link>
      <div className="flex shrink-0 items-center gap-0.5">
        {showActions && (
          <DropdownMenu open={isMoreMenuOpen} onOpenChange={setIsMoreMenuOpen}>
            <TooltipWrapper content="More options">
              <DropdownMenuTrigger asChild>
                <Button
                  variant="minimal"
                  size="icon-2xs"
                  aria-label="More options"
                  className={cn(
                    "rounded text-light-slate hover:text-foreground",
                    isMoreMenuOpen
                      ? "inline-flex"
                      : "hidden group-hover:inline-flex",
                  )}
                >
                  <MoreHorizontal className="size-3.5" />
                </Button>
              </DropdownMenuTrigger>
            </TooltipWrapper>
            <DropdownMenuContent align="start" className="w-40">
              {canCreateProjects && (
                <DropdownMenuItem
                  size="sm"
                  onClick={() => {
                    setIsMoreMenuOpen(false);
                    onRequestEdit(project);
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
                  onClick={() => {
                    setIsMoreMenuOpen(false);
                    onRequestDelete(project);
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
              "group/pin rounded text-light-slate",
              isPinned ? "inline-flex" : "hidden group-hover:inline-flex",
            )}
            onClick={() => onTogglePin(project, !isPinned)}
          >
            {isPinned ? (
              <>
                <Pin className="group-hover/pin:hidden" />
                <PinOff className="hidden group-hover/pin:block" />
              </>
            ) : (
              <Pin />
            )}
          </Button>
        </TooltipWrapper>
      </div>
    </div>
  );
};

export default ProjectMenuItem;
