import React from "react";
import { Dashboard } from "@/types/dashboard";
import useDashboardBatchDeleteMutation from "@/api/dashboards/useDashboardBatchDeleteMutation";
import AddEditCloneDashboardDialog from "@/components/pages-shared/dashboards/AddEditCloneDashboardDialog/AddEditCloneDashboardDialog";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { useRowActionsState } from "@/components/shared/DataTable/hooks/useRowActionsState";
import { RowActionsButtons } from "@/components/shared/DataTable/RowActionsButtons";

type DashboardRowActionsProps = {
  dashboard: Dashboard;
};

export const DashboardRowActions: React.FC<DashboardRowActionsProps> = ({
  dashboard,
}) => {
  const { dialogOpen, open, close } = useRowActionsState();
  const { mutate: deleteDashboardMutate } = useDashboardBatchDeleteMutation();

  const handleDelete = () => {
    deleteDashboardMutate({ ids: [dashboard.id] });
  };

  return (
    <>
      <ConfirmDialog
        open={dialogOpen === "delete"}
        setOpen={close}
        onConfirm={handleDelete}
        title="Delete dashboard"
        description="Are you sure you want to delete this dashboard?"
        confirmText="Delete dashboard"
        confirmButtonVariant="destructive"
      />
      <AddEditCloneDashboardDialog
        mode="edit"
        dashboard={dashboard}
        open={dialogOpen === "edit"}
        setOpen={close}
      />
      <AddEditCloneDashboardDialog
        mode="clone"
        dashboard={dashboard}
        open={dialogOpen === "clone"}
        setOpen={close}
      />
      <RowActionsButtons
        actions={[
          { type: "edit", onClick: open("edit") },
          { type: "duplicate", label: "Clone", onClick: open("clone") },
          { type: "delete", onClick: open("delete") },
        ]}
      />
    </>
  );
};
