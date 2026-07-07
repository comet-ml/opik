import React, { useCallback, useState } from "react";
import { ChevronDown, ChevronUp } from "lucide-react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import AddEditProjectDialog from "@/v2/pages-shared/ProjectsPage/AddEditProjectDialog";
import ProjectMenuContent from "@/v2/layout/ProjectMenu/ProjectMenuContent";

interface ProjectBreadcrumbSelectorProps {
  projectId: string;
  title: string;
}

const ProjectBreadcrumbSelector: React.FC<ProjectBreadcrumbSelectorProps> = ({
  projectId,
  title,
}) => {
  const [open, setOpen] = useState(false);
  const [openCreateDialog, setOpenCreateDialog] = useState(false);

  const handleRequestCreateProject = useCallback(() => {
    setOpen(false);
    setOpenCreateDialog(true);
  }, []);

  return (
    <>
      <DropdownMenu open={open} onOpenChange={setOpen}>
        <DropdownMenuTrigger asChild>
          <button
            type="button"
            className="comet-body-xs flex items-center gap-1 truncate rounded p-1.5 transition-colors hover:text-foreground data-[state=open]:text-foreground"
          >
            <span className="truncate">{title}</span>
            <span className="shrink-0">
              {open ? (
                <ChevronUp className="size-3.5" />
              ) : (
                <ChevronDown className="size-3.5" />
              )}
            </span>
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent
          align="start"
          className="flex max-h-[var(--radix-dropdown-menu-content-available-height)] w-[280px] flex-col overflow-hidden p-1"
        >
          <ProjectMenuContent
            activeProjectId={projectId}
            onClose={() => setOpen(false)}
            onRequestCreateProject={handleRequestCreateProject}
          />
        </DropdownMenuContent>
      </DropdownMenu>
      <AddEditProjectDialog
        open={openCreateDialog}
        setOpen={setOpenCreateDialog}
      />
    </>
  );
};

export default ProjectBreadcrumbSelector;
