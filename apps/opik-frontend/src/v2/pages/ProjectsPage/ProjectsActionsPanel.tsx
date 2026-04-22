import React, { useCallback, useMemo, useRef, useState } from "react";
import { Trash } from "lucide-react";

import { Button } from "@/ui/button";
import { DEFAULT_PROJECT_NAME, Project } from "@/types/projects";
import useProjectBatchDeleteMutation from "@/api/projects/useProjectBatchDeleteMutation";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type ProjectsActionsPanelsProps = {
  projects: Project[];
};

const ProjectsActionsPanel: React.FunctionComponent<
  ProjectsActionsPanelsProps
> = ({ projects }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const deletableProjects = useMemo(
    () => projects.filter((p) => p.name !== DEFAULT_PROJECT_NAME),
    [projects],
  );
  const disabled = !deletableProjects.length;

  const { mutate } = useProjectBatchDeleteMutation();

  const deleteProjectsHandler = useCallback(() => {
    mutate({
      ids: deletableProjects.map((p) => p.id),
    });
  }, [deletableProjects, mutate]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteProjectsHandler}
        title="Delete projects"
        description="Deleting projects will also remove all the traces and their data. This action can’t be undone. Are you sure you want to continue?"
        confirmText="Delete projects"
        confirmButtonVariant="destructive"
      />
      <TooltipWrapper content="Delete">
        <Button
          variant="outline"
          size="icon-sm"
          onClick={() => {
            setOpen(true);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <Trash />
        </Button>
      </TooltipWrapper>
    </div>
  );
};

export default ProjectsActionsPanel;
