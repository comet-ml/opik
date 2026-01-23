import React from "react";
import { Row } from "@tanstack/react-table";
import { useNavigate } from "@tanstack/react-router";

import { Alert } from "@/types/alerts";
import useAlertsBatchDeleteMutation from "@/api/alerts/useAlertsBatchDeleteMutation";
import useAppStore from "@/store/AppStore";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { useRowActionsState } from "@/components/shared/DataTable/hooks/useRowActionsState";
import { RowActionsButtons } from "@/components/shared/DataTable/RowActionsButtons";

interface AlertsRowActionsProps {
  row: Row<Alert>;
}

const AlertsRowActions: React.FC<AlertsRowActionsProps> = ({ row }) => {
  const { dialogOpen, open, close } = useRowActionsState();
  const alert = row.original;
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { mutate } = useAlertsBatchDeleteMutation();

  const handleEdit = () => {
    if (!alert.id) return;
    navigate({
      to: "/$workspaceName/alerts/$alertId",
      params: { workspaceName, alertId: alert.id },
      search: (prev) => prev,
    });
  };

  const handleDelete = () => {
    if (!alert.id) return;
    mutate({ ids: [alert.id] });
    close();
  };

  return (
    <>
      <ConfirmDialog
        open={dialogOpen === "delete"}
        setOpen={close}
        onConfirm={handleDelete}
        title="Delete alert"
        description="Are you sure you want to delete this alert? This action cannot be undone."
        confirmText="Delete alert"
        confirmButtonVariant="destructive"
      />
      <RowActionsButtons
        actions={[
          { type: "edit", onClick: handleEdit },
          { type: "delete", onClick: open("delete") },
        ]}
      />
    </>
  );
};

export default AlertsRowActions;
