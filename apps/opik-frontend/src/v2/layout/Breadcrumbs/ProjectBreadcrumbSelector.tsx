import React, { useCallback, useState } from "react";
import { ChevronDown, ChevronUp } from "lucide-react";

import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
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
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
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
        </PopoverTrigger>
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
            activeProjectId={projectId}
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

export default ProjectBreadcrumbSelector;
