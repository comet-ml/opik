import { Trash } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import useOptimizationBatchDeleteMutation from "@/api/optimizations/useOptimizationBatchDeleteMutation";
import { Optimization } from "@/types/optimizations";
import RowActions, { RowAction } from "@/shared/DataTable/RowActions";

const OptimizationRowActionsCell: React.FunctionComponent<
  CellContext<Optimization, unknown>
> = (context) => {
  const resetKeyRef = useRef(0);
  const organisation = context.row.original;
  const [open, setOpen] = useState<boolean>(false);

  const { mutate } = useOptimizationBatchDeleteMutation();

  const deleteOptimizationHandler = useCallback(() => {
    mutate({
      ids: [organisation.id],
    });
  }, [organisation, mutate]);

  const rowActions: RowAction[] = [
    {
      id: "delete",
      label: "Delete",
      icon: Trash,
      onClick: () => {
        setOpen(true);
        resetKeyRef.current = resetKeyRef.current + 1;
      },
      destructive: true,
    },
  ];

  return (
    <>
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteOptimizationHandler}
        title="Delete optimization"
        description="Deleting an optimization run will remove all its trials and their data. Related traces won’t be affected. This action can’t be undone. Are you sure you want to continue?"
        confirmText="Delete optimization"
        confirmButtonVariant="destructive"
      />
      <RowActions actions={rowActions} />
    </>
  );
};

export default OptimizationRowActionsCell;
