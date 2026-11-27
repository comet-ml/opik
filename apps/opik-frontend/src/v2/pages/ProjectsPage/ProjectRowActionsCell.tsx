import { Pencil, Trash } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import { DEFAULT_PROJECT_NAME, Project } from "@/types/projects";
import { CellContext } from "@tanstack/react-table";
import AddEditProjectDialog from "@/v2/pages-shared/ProjectsPage/AddEditProjectDialog";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import useProjectDeleteMutation from "@/api/projects/useProjectDeleteMutation";
import RowActions, { RowAction } from "@/shared/DataTable/RowActions";
import { usePermissions } from "@/contexts/PermissionsContext";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { setActiveProject } from "@/hooks/useActiveProjectInitializer";

export const ProjectRowActionsCell: React.FC<CellContext<Project, unknown>> = (
  context,
) => {
  const resetKeyRef = useRef(0);
  const project = context.row.original;
  const [open, setOpen] = useState<boolean | number>(false);

  const {
    permissions: { canCreateProjects, canDeleteProjects },
  } = usePermissions();

  const isDefaultProject = project.name === DEFAULT_PROJECT_NAME;
  const canDelete = canDeleteProjects && !isDefaultProject;

  const { mutate } = useProjectDeleteMutation();
  const activeProjectId = useActiveProjectId();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const deleteProjectHandler = useCallback(() => {
    mutate(
      { projectId: project.id },
      {
        onSuccess: () => {
          if (project.id === activeProjectId) {
            setActiveProject(workspaceName, null);
          }
        },
      },
    );
  }, [project.id, mutate, activeProjectId, workspaceName]);

  const openDialog = (dialog: number) => {
    setOpen(dialog);
    resetKeyRef.current = resetKeyRef.current + 1;
  };
  const rowActions: RowAction[] = [
    ...(canCreateProjects
      ? [
          {
            id: "edit",
            label: "Edit",
            icon: Pencil,
            onClick: () => openDialog(2),
          },
        ]
      : []),
    ...(canDelete
      ? [
          {
            id: "delete",
            label: "Delete",
            icon: Trash,
            onClick: () => openDialog(1),
            destructive: true,
          },
        ]
      : []),
  ];

  return (
    <>
      <AddEditProjectDialog
        key={`add-${resetKeyRef.current}`}
        project={project}
        open={open === 2}
        setOpen={setOpen}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteProjectHandler}
        title="Delete project"
        description="Deleting a project will also remove all the traces and their data. This action can’t be undone. Are you sure you want to continue?"
        confirmText="Delete project"
        confirmButtonVariant="destructive"
      />
      <RowActions actions={rowActions} />
    </>
  );
};
