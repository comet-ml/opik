import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";
import { useTranslation } from "react-i18next";

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
  const { t } = useTranslation();
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
        title={t("projects.deleteProjects")}
        description={t("projects.deleteProjectsConfirm")}
        confirmText={t("projects.actions.delete")}
        confirmButtonVariant="destructive"
      />
      <TooltipWrapper content={t("projects.actions.deleteTooltip")}>
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
