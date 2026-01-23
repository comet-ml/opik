import React from "react";
import AddEditProjectDialog from "@/components/pages/ProjectsPage/AddEditProjectDialog";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { Project } from "@/types/projects";
import useProjectDeleteMutation from "@/api/projects/useProjectDeleteMutation";
import { useRowActionsState } from "@/components/shared/DataTable/hooks/useRowActionsState";
import { RowActionsButtons } from "@/components/shared/DataTable/RowActionsButtons";

type ProjectRowActionsProps = {
  project: Project;
};

export const ProjectRowActions: React.FC<ProjectRowActionsProps> = ({
  project,
}) => {
  const { dialogOpen, open, close } = useRowActionsState();
  const { mutate } = useProjectDeleteMutation();

  const handleDelete = () => {
    mutate({ projectId: project.id });
  };

  return (
    <>
      <ConfirmDialog
        open={dialogOpen === "delete"}
        setOpen={close}
        onConfirm={handleDelete}
        title="Delete project"
        description="Deleting a project will also remove all the traces and their data. This action can't be undone. Are you sure you want to continue?"
        confirmText="Delete project"
        confirmButtonVariant="destructive"
      />
      <AddEditProjectDialog
        project={project}
        open={dialogOpen === "edit"}
        setOpen={close}
      />
      <RowActionsButtons
        actions={[
          { type: "edit", onClick: open("edit") },
          { type: "delete", onClick: open("delete") },
        ]}
      />
    </>
  );
};
