import React from "react";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import EditVersionDialog from "./EditVersionDialog";
import { DatasetVersion } from "@/types/datasets";
import { isLatestVersionTag } from "@/constants/datasets";
import useRestoreDatasetVersionMutation from "@/api/datasets/useRestoreDatasetVersionMutation";
import { useRowActionsState } from "@/components/shared/DataTable/hooks/useRowActionsState";
import { RowActionsButtons } from "@/components/shared/DataTable/RowActionsButtons";

type VersionRowActionsProps = {
  version: DatasetVersion;
  datasetId: string;
};

const VersionRowActions: React.FC<VersionRowActionsProps> = ({
  version,
  datasetId,
}) => {
  const { dialogOpen, open, close } = useRowActionsState();
  const restoreMutation = useRestoreDatasetVersionMutation();

  const isLatestVersion = version.tags?.some(isLatestVersionTag) ?? false;

  const handleRestore = () => {
    restoreMutation.mutate({
      datasetId,
      versionRef: version.version_hash,
    });
    close();
  };

  const actions = React.useMemo(
    () => [
      { type: "edit" as const, onClick: open("edit") },
      ...(!isLatestVersion
        ? [{ type: "restore" as const, onClick: open("restore") }]
        : []),
    ],
    [isLatestVersion, open],
  );

  return (
    <>
      <ConfirmDialog
        open={dialogOpen === "restore"}
        setOpen={close}
        onConfirm={handleRestore}
        title="Restore version"
        description={`Restoring this version will create a new version based on version ${version.version_name}. All previous versions will stay in your history.`}
        confirmText="Restore version"
        confirmButtonVariant="default"
      />
      <EditVersionDialog
        open={dialogOpen === "edit"}
        setOpen={close}
        version={version}
        datasetId={datasetId}
      />
      <RowActionsButtons actions={actions} />
    </>
  );
};

export default VersionRowActions;
