import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { MoreHorizontal, Trash } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
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

  const menuButton = (
    <Button variant="ghost" size="icon" className="size-8">
      <MoreHorizontal className="size-4" />
      <span className="sr-only">Actions menu</span>
    </Button>
  );

  const deleteMenuItem = (
    <DropdownMenuItem
      onClick={handleDeleteClick}
      disabled={isInvitedByEmail}
      variant="destructive"
    >
      <Trash className="mr-2 size-4" />
      Delete
    </DropdownMenuItem>
  );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
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
      <DropdownMenu>
        <DropdownMenuTrigger asChild>{menuButton}</DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          {isInvitedByEmail ? (
            <TooltipWrapper content="Cannot remove users invited by email">
              <div className="w-full">{deleteMenuItem}</div>
            </TooltipWrapper>
          ) : (
            deleteMenuItem
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
};

export default WorkspaceMemberActionsCell;
