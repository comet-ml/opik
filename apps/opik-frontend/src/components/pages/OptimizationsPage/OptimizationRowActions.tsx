import React from "react";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useOptimizationBatchDeleteMutation from "@/api/optimizations/useOptimizationBatchDeleteMutation";
import { GroupedOptimization } from "@/hooks/useGroupedOptimizationsList";
import { useRowActionsState } from "@/components/shared/DataTable/hooks/useRowActionsState";
import { RowActionsButtons } from "@/components/shared/DataTable/RowActionsButtons";

type OptimizationRowActionsProps = {
  optimization: GroupedOptimization;
};

const OptimizationRowActions: React.FC<OptimizationRowActionsProps> = ({
  optimization,
}) => {
  const { dialogOpen, open, close } = useRowActionsState();
  const { mutate } = useOptimizationBatchDeleteMutation();

  const handleDelete = () => {
    mutate({ ids: [optimization.id] });
  };

  return (
    <>
      <ConfirmDialog
        open={dialogOpen === "delete"}
        setOpen={close}
        onConfirm={handleDelete}
        title="Delete optimization"
        description="Deleting an optimization run will remove all its trials and their data. Related traces won't be affected. This action can't be undone. Are you sure you want to continue?"
        confirmText="Delete optimization"
        confirmButtonVariant="destructive"
      />
      <RowActionsButtons
        actions={[{ type: "delete", onClick: open("delete") }]}
      />
    </>
  );
};

export default OptimizationRowActions;
