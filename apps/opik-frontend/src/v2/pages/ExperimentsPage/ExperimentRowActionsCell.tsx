import { Pencil, Trash } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import useExperimentBatchDeleteMutation from "@/api/datasets/useExperimentBatchDeleteMutation";
import { GroupedExperiment } from "@/hooks/useGroupedExperimentsList";
import RowActions, { RowAction } from "@/shared/DataTable/RowActions";
import { UpdateExperimentDialog } from "@/shared/UpdateExperimentDialog/UpdateExperimentDialog";
import useExperimentUpdateMutation from "@/api/datasets/useExperimentUpdate";
import { usePermissions } from "@/contexts/PermissionsContext";

const ExperimentRowActionsCell: React.FunctionComponent<
  CellContext<GroupedExperiment, unknown>
> = (context) => {
  const resetKeyRef = useRef(0);
  const experiment = context.row.original;
  const {
    permissions: { canCreateExperiments },
  } = usePermissions();
  const [open, setOpen] = useState<boolean | number>(false);

  const experimentBatchDeleteMutation = useExperimentBatchDeleteMutation();
  const experimentUpdateMutation = useExperimentUpdateMutation();

  const deleteExperimentsHandler = useCallback(() => {
    experimentBatchDeleteMutation.mutate({
      ids: [experiment.id],
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [experiment]);

  const updateExperimentHandler = useCallback(
    (name: string, configuration: object) => {
      experimentUpdateMutation.mutate({
        experiment: {
          id: experiment.id,
          name: name,
          metadata: configuration,
        },
      });
    },
    [experiment, experimentUpdateMutation],
  );

  if (!canCreateExperiments) {
    return null;
  }

  const openDialog = (dialog: number) => {
    setOpen(dialog);
    resetKeyRef.current = resetKeyRef.current + 1;
  };
  const rowActions: RowAction[] = [
    {
      id: "edit",
      label: "Edit",
      icon: Pencil,
      onClick: () => openDialog(2),
    },
    {
      id: "delete",
      label: "Delete",
      icon: Trash,
      onClick: () => openDialog(1),
      destructive: true,
    },
  ];

  return (
    <>
      <UpdateExperimentDialog
        key={`edit-${resetKeyRef.current}`}
        open={open === 2}
        setOpen={setOpen}
        onConfirm={updateExperimentHandler}
        latestName={experiment.name}
        latestConfiguration={experiment.metadata}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteExperimentsHandler}
        title="Delete experiment"
        description="Deleting an experiment will remove all samples in the experiment. Related traces won't be affected. This action can't be undone. Are you sure you want to continue?"
        confirmText="Delete experiment"
        confirmButtonVariant="destructive"
      />
      <RowActions actions={rowActions} />
    </>
  );
};

export default ExperimentRowActionsCell;
