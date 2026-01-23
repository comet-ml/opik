import React from "react";
import ManageAIProviderDialog from "@/components/pages-shared/llm/ManageAIProviderDialog/ManageAIProviderDialog";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { ProviderObject } from "@/types/providers";
import useProviderKeysDeleteMutation from "@/api/provider-keys/useProviderKeysDeleteMutation";
import { useRowActionsState } from "@/components/shared/DataTable/hooks/useRowActionsState";
import { RowActionsButtons } from "@/components/shared/DataTable/RowActionsButtons";

type AIProvidersRowActionsProps = {
  providerKey: ProviderObject;
};

const AIProvidersRowActions: React.FC<AIProvidersRowActionsProps> = ({
  providerKey,
}) => {
  const { dialogOpen, open, close } = useRowActionsState();

  const isReadOnly = providerKey.read_only === true;

  const { mutate: deleteProviderKey } = useProviderKeysDeleteMutation();

  const handleDelete = () => {
    deleteProviderKey({
      providerId: providerKey.id,
    });
  };

  if (isReadOnly) {
    return null;
  }

  return (
    <>
      <ConfirmDialog
        open={dialogOpen === "delete"}
        setOpen={close}
        onConfirm={handleDelete}
        title="Delete configuration"
        description="This configuration is shared across the workspace. Deleting it will remove access for everyone. This action can't be undone. Are you sure you want to proceed?"
        confirmText="Delete configuration"
        confirmButtonVariant="destructive"
      />
      <ManageAIProviderDialog
        providerKey={providerKey}
        open={dialogOpen === "edit"}
        setOpen={close}
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

export default AIProvidersRowActions;
