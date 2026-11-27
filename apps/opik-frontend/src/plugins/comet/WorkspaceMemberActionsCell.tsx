import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { Trash } from "lucide-react";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import RowActions, { RowAction } from "@/shared/DataTable/RowActions";
import { WorkspaceMember } from "@/plugins/comet/types";
import { useRemoveFromTeamMutation } from "@/plugins/comet/api/useRemoveFromTeamMutation";
import useWorkspace from "@/plugins/comet/useWorkspace";

const WorkspaceMemberActionsCell = (
  context: CellContext<WorkspaceMember, unknown>,
) => {
  const row = context.row.original;
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState(false);

  const workspace = useWorkspace();
  const { mutate: removeFromTeam } = useRemoveFromTeamMutation();

  const userName = row.userName || row.email;
  const isInvitedByEmail = !row.isMember;

  const deleteUserHandler = useCallback(() => {
    if (workspace?.workspaceId && userName) {
      removeFromTeam({
        teamId: workspace.workspaceId,
        userName,
      });
    }
  }, [workspace?.workspaceId, userName, removeFromTeam]);

  const handleDeleteClick = useCallback(() => {
    setOpen(true);
    resetKeyRef.current = resetKeyRef.current + 1;
  }, []);

  const displayName = row.userName || row.email || "User";

  const rowActions: RowAction[] = [
    {
      id: "delete",
      label: "Delete",
      icon: Trash,
      onClick: handleDeleteClick,
      destructive: true,
      ...(isInvitedByEmail && {
        disabled: true,
        disabledReason: "Cannot remove users invited by email",
      }),
    },
  ];

  return (
    <>
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteUserHandler}
        title="Remove user from workspace"
        description={`Are you sure you want to remove ${displayName} from this workspace?`}
        confirmText="Remove user"
        confirmButtonVariant="destructive"
      />
      <RowActions actions={rowActions} />
    </>
  );
};

export default WorkspaceMemberActionsCell;
