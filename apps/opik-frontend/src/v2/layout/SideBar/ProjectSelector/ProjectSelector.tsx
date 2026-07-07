import React, { useCallback, useState } from "react";
import { ChevronDown, ChevronUp } from "lucide-react";
import { Link } from "@tanstack/react-router";

import { cn } from "@/lib/utils";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { Button } from "@/ui/button";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { Spinner } from "@/ui/spinner";
import useProjectById from "@/api/projects/useProjectById";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import AddEditProjectDialog from "@/v2/pages-shared/ProjectsPage/AddEditProjectDialog";
import ProjectAvatar from "@/shared/ProjectIcon/ProjectAvatar";
import ProjectMenuContent from "@/v2/layout/ProjectMenu/ProjectMenuContent";

interface ProjectSelectorProps {
  expanded?: boolean;
}

const ProjectSelector: React.FC<ProjectSelectorProps> = ({
  expanded = true,
}) => {
  const [open, setOpen] = useState(false);
  const [openCreateDialog, setOpenCreateDialog] = useState(false);
  const activeProjectId = useActiveProjectId();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: activeProject, isPending: isProjectPending } = useProjectById(
    { projectId: activeProjectId! },
    { enabled: !!activeProjectId },
  );

  const isLoading = !!activeProjectId && isProjectPending;

  const handleRequestCreateProject = useCallback(() => {
    setOpen(false);
    setOpenCreateDialog(true);
  }, []);

  const renderChevronIcon = () =>
    open ? (
      <ChevronUp className="size-3.5" />
    ) : (
      <ChevronDown className="size-3.5" />
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

  const renderExpandedNoProject = () => (
    <PopoverTrigger asChild>
      <button
        className={cn(
          "flex w-full items-center gap-1.5 rounded-md px-1 py-0.5 transition-colors hover:bg-primary-foreground",
          open && "bg-primary-foreground",
        )}
      >
        <ProjectAvatar projectId={activeProjectId} size="lg" />
        <div className="flex min-w-0 flex-1 flex-col items-stretch">
          <span className="comet-body-xs-accented text-left text-light-slate">
            Project
          </span>
          <span className="comet-body-s-accented truncate text-left text-muted-slate">
            Select project
          </span>
        </div>
        <span className="shrink-0 text-light-slate">{renderChevronIcon()}</span>
      </button>
    </PopoverTrigger>
  );

  const renderExpandedActiveProject = () => {
    if (!activeProject) return null;
    return (
      <PopoverTrigger asChild>
        <button
          className={cn(
            "flex w-full items-center gap-1.5 rounded-md px-1 py-0.5 transition-colors hover:bg-primary-foreground",
            open && "bg-primary-foreground",
          )}
          aria-label="Open project selector"
        >
          <ProjectAvatar projectId={activeProjectId} size="lg" />
          <div className="flex min-w-0 flex-1 flex-col items-stretch">
            <span className="comet-body-xs-accented text-left text-light-slate">
              Project
            </span>
            <TooltipWrapper content={activeProject.name}>
              <span className="comet-body-s-accented block w-full truncate text-left text-foreground">
                {activeProject.name}
              </span>
            </TooltipWrapper>
          </div>
          <span className="shrink-0 text-light-slate">
            {renderChevronIcon()}
          </span>
        </button>
      </PopoverTrigger>
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
          <ProjectMenuContent
            activeProjectId={activeProjectId}
            onClose={() => setOpen(false)}
            onRequestCreateProject={handleRequestCreateProject}
          />
        </PopoverContent>
      </Popover>
      <AddEditProjectDialog
        open={openCreateDialog}
        setOpen={setOpenCreateDialog}
      />
    </>
  );
};

export default ProjectSelector;
