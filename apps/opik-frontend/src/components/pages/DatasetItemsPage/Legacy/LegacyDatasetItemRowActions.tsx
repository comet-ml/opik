import React from "react";
import { DatasetItem } from "@/types/datasets";
import { useDatasetIdFromURL } from "@/hooks/useDatasetIdFromURL";
import useAppStore from "@/store/AppStore";
import useDatasetItemDeleteMutation from "@/api/datasets/useDatasetItemDeleteMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { useRowActionsState } from "@/components/shared/DataTable/hooks/useRowActionsState";
import { RowActionsButtons } from "@/components/shared/DataTable/RowActionsButtons";

type LegacyDatasetItemRowActionsProps = {
  datasetItem: DatasetItem;
};

export const LegacyDatasetItemRowActions: React.FC<
  LegacyDatasetItemRowActionsProps
> = ({ datasetItem }) => {
  const datasetId = useDatasetIdFromURL();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { dialogOpen, open, close } = useRowActionsState();
  const datasetItemDeleteMutation = useDatasetItemDeleteMutation();

  const handleDelete = () => {
    datasetItemDeleteMutation.mutate({
      datasetId,
      datasetItemId: datasetItem.id,
      workspaceName,
    });
  };

  return (
    <>
      <ConfirmDialog
        open={dialogOpen === "delete"}
        setOpen={close}
        onConfirm={handleDelete}
        title="Delete dataset item"
        description="Deleting a dataset item will also remove the related sample data from any linked experiments. This action can't be undone. Are you sure you want to continue?"
        confirmText="Delete dataset item"
        confirmButtonVariant="destructive"
      />
      <RowActionsButtons
        actions={[{ type: "delete", onClick: open("delete") }]}
      />
    </>
  );
};
