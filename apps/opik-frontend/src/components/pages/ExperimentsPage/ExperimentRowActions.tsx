import React from "react";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { UpdateExperimentDialog } from "@/components/shared/UpdateExperimentDialog/UpdateExperimentDialog";
import useExperimentBatchDeleteMutation from "@/api/datasets/useExperimentBatchDeleteMutation";
import useExperimentUpdateMutation from "@/api/datasets/useExperimentUpdate";
import { GroupedExperiment } from "@/hooks/useGroupedExperimentsList";
import { useRowActionsState } from "@/components/shared/DataTable/hooks/useRowActionsState";
import { RowActionsButtons } from "@/components/shared/DataTable/RowActionsButtons";

type ExperimentRowActionsProps = {
  experiment: GroupedExperiment;
};

const ExperimentRowActions: React.FC<ExperimentRowActionsProps> = ({
  experiment,
}) => {
  const { dialogOpen, open, close } = useRowActionsState();
  const experimentBatchDeleteMutation = useExperimentBatchDeleteMutation();
  const experimentUpdateMutation = useExperimentUpdateMutation();

  const handleDelete = () => {
    experimentBatchDeleteMutation.mutate({ ids: [experiment.id] });
    close();
  };

  const handleUpdate = (name: string, configuration: object) => {
    experimentUpdateMutation.mutate({
      experiment: {
        id: experiment.id,
        name: name,
        metadata: configuration,
      },
    });
  };

  return (
    <>
      <ConfirmDialog
        open={dialogOpen === "delete"}
        setOpen={close}
        onConfirm={handleDelete}
        title="Delete experiment"
        description="Deleting an experiment will remove all samples in the experiment. Related traces won't be affected. This action can't be undone. Are you sure you want to continue?"
        confirmText="Delete experiment"
        confirmButtonVariant="destructive"
      />
      <UpdateExperimentDialog
        open={dialogOpen === "edit"}
        setOpen={close}
        onConfirm={handleUpdate}
        latestName={experiment.name}
        latestConfiguration={experiment.metadata}
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

export default ExperimentRowActions;
