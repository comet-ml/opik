import React, { useCallback, useRef, useState } from "react";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { WorkspaceMember } from "@/plugins/comet/types";
import { useRemoveFromTeamMutation } from "@/plugins/comet/api/useRemoveFromTeamMutation";
import useWorkspace from "@/plugins/comet/useWorkspace";
import { RowActionsButtons } from "@/components/shared/DataTable/RowActionsButtons";

type WorkspaceMemberRowActionsProps = {
  member: WorkspaceMember;
};

const WorkspaceMemberRowActions: React.FC<WorkspaceMemberRowActionsProps> = ({
  member,
}) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState(false);

  const workspace = useWorkspace();
  const { mutate: removeFromTeam } = useRemoveFromTeamMutation();

  const userName = member.userName || member.email;
  const isInvitedByEmail = !member.isMember;
  const displayName = member.userName || member.email || "User";

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

  if (isInvitedByEmail) {
    return null;
  }

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
      <RowActionsButtons
        actions={[{ type: "delete", onClick: handleDeleteClick }]}
      />
    </>
  );
};

export default WorkspaceMemberRowActions;
