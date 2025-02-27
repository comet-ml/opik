import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Project } from "@/types/projects";
import useProjectBatchDeleteMutation from "@/api/projects/useProjectBatchDeleteMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type ProjectsActionsPanelsProps = {
  projects: Project[];
};

const ProjectsActionsPanel: React.FunctionComponent<
  ProjectsActionsPanelsProps
> = ({ projects }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !projects?.length;

  const { mutate } = useProjectBatchDeleteMutation();

  const deleteProjectsHandler = useCallback(() => {
    mutate({
      ids: projects.map((p) => p.id),
    });
  }, [projects, mutate]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteProjectsHandler}
        title="Delete projects"
        description="Are you sure you want to delete all selected projects?"
        confirmText="Delete projects"
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
