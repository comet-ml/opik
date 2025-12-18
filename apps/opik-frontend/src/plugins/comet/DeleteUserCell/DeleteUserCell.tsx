import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { WorkspaceMember } from "@/plugins/comet/types";
import { useRemoveFromTeamMutation } from "@/plugins/comet/api/useRemoveFromTeamMutation";
import useWorkspace from "@/plugins/comet/useWorkspace";

const DeleteUserCell = (context: CellContext<WorkspaceMember, unknown>) => {
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

  const tooltipContent = isInvitedByEmail
    ? "Cannot remove users invited by email"
    : "Remove user from workspace";

  const button = (
    <Button
      variant="ghost"
      size="icon"
      onClick={handleDeleteClick}
      disabled={isInvitedByEmail}
      className="size-8"
    >
      <Trash2 className="size-4" />
    </Button>
  );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-start p-0"
      stopClickPropagation
    >
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteUserHandler}
        title="Remove user from workspace"
        description={`Are you sure you want to remove ${displayName} from this workspace? This action can't be undone.`}
        confirmText="Remove user"
        confirmButtonVariant="destructive"
      />
      <TooltipWrapper content={tooltipContent}>
        <span className="inline-block cursor-not-allowed">{button}</span>
      </TooltipWrapper>
    </CellWrapper>
  );
};

export default DeleteUserCell;
