import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { Pencil, Copy, Trash } from "lucide-react";

import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import RowActions, { RowAction } from "@/shared/DataTable/RowActions";
import { Dashboard } from "@/types/dashboard";
import useDashboardBatchDeleteMutation from "@/api/dashboards/useDashboardBatchDeleteMutation";
import AddEditCloneDashboardDialog from "@/v2/pages-shared/dashboards/AddEditCloneDashboardDialog/AddEditCloneDashboardDialog";
import { usePermissions } from "@/contexts/PermissionsContext";

export const DashboardRowActionsCell: React.FunctionComponent<
  CellContext<Dashboard, unknown>
> = (context) => {
  const resetKeyRef = useRef(0);
  const dashboard = context.row.original;

  const {
    permissions: {
      canCreateDashboards,
      canEditDashboards,
      canDeleteDashboards,
    },
  } = usePermissions();

  const { mutate: deleteDashboardMutate } = useDashboardBatchDeleteMutation();

  const [openEdit, setOpenEdit] = useState<boolean>(false);
  const [openClone, setOpenClone] = useState<boolean>(false);
  const [openConfirmDialog, setOpenConfirmDialog] = useState<boolean>(false);

  const deleteDashboard = useCallback(() => {
    deleteDashboardMutate({
      ids: [dashboard.id],
    });
  }, [dashboard, deleteDashboardMutate]);

  const handleEdit = useCallback(() => {
    setOpenEdit(true);
    resetKeyRef.current = resetKeyRef.current + 1;
  }, []);

  const handleClone = useCallback(() => {
    setOpenClone(true);
    resetKeyRef.current = resetKeyRef.current + 1;
  }, []);

  const handleDelete = useCallback(() => {
    setOpenConfirmDialog(true);
  }, []);

  if (!canEditDashboards && !canCreateDashboards && !canDeleteDashboards) {
    return null;
  }

  const rowActions: RowAction[] = [
    ...(canEditDashboards
      ? [{ id: "edit", label: "Edit", icon: Pencil, onClick: handleEdit }]
      : []),
    ...(canCreateDashboards
      ? [{ id: "clone", label: "Clone", icon: Copy, onClick: handleClone }]
      : []),
    ...(canDeleteDashboards
      ? [
          {
            id: "delete",
            label: "Delete",
            icon: Trash,
            onClick: handleDelete,
            destructive: true,
          },
        ]
      : []),
  ];

  return (
    <>
      <AddEditCloneDashboardDialog
        mode="edit"
        key={`edit-${resetKeyRef.current}`}
        dashboard={dashboard}
        open={openEdit}
        setOpen={setOpenEdit}
      />
      <AddEditCloneDashboardDialog
        mode="clone"
        key={`clone-${resetKeyRef.current}`}
        dashboard={dashboard}
        open={openClone}
        setOpen={setOpenClone}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={openConfirmDialog}
        setOpen={setOpenConfirmDialog}
        onConfirm={deleteDashboard}
        title="Delete dashboard"
        description="Are you sure you want to delete this dashboard? This action cannot be undone."
        confirmText="Delete dashboard"
        confirmButtonVariant="destructive"
      />
      <RowActions actions={rowActions} />
    </>
  );
};
