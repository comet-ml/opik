import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { Pencil, Trash } from "lucide-react";
import { useNavigate } from "@tanstack/react-router";

import { Alert } from "@/types/alerts";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import RowActions, { RowAction } from "@/shared/DataTable/RowActions";
import useAlertsBatchDeleteMutation from "@/api/alerts/useAlertsBatchDeleteMutation";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";

const AlertsRowActionsCell: React.FunctionComponent<
  CellContext<Alert, unknown>
> = (context) => {
  const resetKeyRef = useRef(0);
  const alert = context.row.original;
  const [open, setOpen] = useState<boolean | number>(false);
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();

  const { mutate } = useAlertsBatchDeleteMutation();

  const deleteAlertHandler = useCallback(() => {
    if (!alert.id) return;

    mutate({ ids: [alert.id] });
  }, [alert.id, mutate]);

  const handleEditClick = useCallback(() => {
    if (!alert.id) return;

    navigate({
      to: "/$workspaceName/projects/$projectId/alerts/$alertId",
      params: { workspaceName, projectId: activeProjectId!, alertId: alert.id },
      search: (prev: Record<string, unknown>) => prev,
    });
  }, [navigate, workspaceName, activeProjectId, alert.id]);

  const rowActions: RowAction[] = [
    {
      id: "edit",
      label: "Edit",
      icon: Pencil,
      onClick: handleEditClick,
    },
    {
      id: "delete",
      label: "Delete",
      icon: Trash,
      onClick: () => {
        setOpen(1);
        resetKeyRef.current = resetKeyRef.current + 1;
      },
      destructive: true,
    },
  ];

  return (
    <>
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteAlertHandler}
        title="Delete alert"
        description="Are you sure you want to delete this alert? This action cannot be undone."
        confirmText="Delete alert"
        confirmButtonVariant="destructive"
      />
      <RowActions actions={rowActions} />
    </>
  );
};

export default AlertsRowActionsCell;
