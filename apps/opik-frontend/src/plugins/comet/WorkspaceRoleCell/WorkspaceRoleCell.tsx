import React, { useState, useMemo, useEffect, useRef } from "react";
import { CellContext } from "@tanstack/react-table";
import debounce from "lodash/debounce";
import { Select, SelectTrigger, SelectValue } from "@/components/ui/select";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import useUserPermission from "@/plugins/comet/useUserPermission";
import { getKeyForChangingRole } from "@/plugins/comet/lib/permissions";
import { UserPermission, WorkspaceMember } from "@/plugins/comet/types";
import useWorkspace from "@/plugins/comet/useWorkspace";
import WorkspaceRolePopover from "@/plugins/comet/WorkspaceRolePopover";
import { useUpdateWorkspaceUsersPermissionsMutation } from "@/plugins/comet/api/useUpdateWorkspaceUsersPermissionsMutation";
import { useLoggedInUserName } from "@/store/AppStore";
import useManageUsersRolePopover from "./useManageUsersRolePopover";

const WorkspaceRoleCell = (context: CellContext<WorkspaceMember, string>) => {
  const value = context.getValue();
  const row = context.row.original;
  const isInvitedByEmail = !row.isMember;

  const currentUserName = useLoggedInUserName();

  const { getPermissionStatus } = useUserPermission();

  const workspace = useWorkspace();

  const [popoverData, setPopoverData] = useState<WorkspaceMember | null>(null);
  const [isSelectOpen, setIsSelectOpen] = useState(false);
  const shouldKeepOpenRef = useRef(false);

  const { mutate: updateWorkspaceUsersPermissions } =
    useUpdateWorkspaceUsersPermissionsMutation();

  const debouncedUpdatePermissions = useMemo(
    () =>
      debounce(
        (
          workspaceId: string,
          userName: string,
          permissions: UserPermission[],
        ) => {
          updateWorkspaceUsersPermissions({
            workspaceId,
            usersPermissions: [
              {
                userName,
                permissions,
              },
            ],
          });
        },
        500,
      ),
    [updateWorkspaceUsersPermissions],
  );

  useEffect(() => {
    return () => {
      debouncedUpdatePermissions.cancel();
    };
  }, [debouncedUpdatePermissions]);

  useEffect(() => {
    if (!popoverData) {
      setPopoverData(row);
    }
  }, [row, popoverData]);

  const ifChangeWsRoleDisabled =
    !currentUserName ||
    !getPermissionStatus(
      getKeyForChangingRole(
        currentUserName,
        popoverData?.userName || popoverData?.email || "",
      ),
    );

  const setPermissions = (newPermissions: UserPermission[]) => {
    const userName = popoverData?.userName || popoverData?.email;

    setPopoverData((data) => ({
      ...data!,
      permissions: newPermissions,
    }));

    if (workspace?.workspaceId && userName) {
      debouncedUpdatePermissions(
        workspace?.workspaceId,
        userName,
        newPermissions,
      );
    }
  };

  const decisionTreeProps = useManageUsersRolePopover(
    popoverData?.permissions || [],
    popoverData?.userName || "",
    ifChangeWsRoleDisabled,
    !!popoverData?.isAdmin,
    setPermissions,
  );

  const trigger = (
    <SelectTrigger
      className="-ml-1 h-auto min-w-0 border-none bg-transparent px-1 py-0.5 shadow-none hover:bg-transparent focus:ring-2 focus:ring-offset-1 disabled:cursor-not-allowed disabled:opacity-50"
      onClick={(e) => {
        e.stopPropagation();
        if (isInvitedByEmail) {
          e.preventDefault();
        }
      }}
      disabled={isInvitedByEmail}
    >
      <SelectValue>
        <span className="min-w-0 truncate">{value}</span>
      </SelectValue>
    </SelectTrigger>
  );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <Select
        value={decisionTreeProps.controlValue || ""}
        open={isSelectOpen}
        onOpenChange={(open) => {
          if (!open && shouldKeepOpenRef.current) {
            shouldKeepOpenRef.current = false;
            return;
          }
          setIsSelectOpen(open);
          if (open && !isInvitedByEmail) {
            setPopoverData(row);
          }
        }}
        onValueChange={(newValue) => {
          const syntheticEvent = {
            target: { value: newValue },
          } as React.ChangeEvent<HTMLInputElement>;
          decisionTreeProps.onChange(syntheticEvent);
          shouldKeepOpenRef.current = true;
        }}
        disabled={isInvitedByEmail}
      >
        {isInvitedByEmail ? (
          <TooltipWrapper content="Cannot change roles for users invited by email">
            {trigger}
          </TooltipWrapper>
        ) : (
          trigger
        )}
        <WorkspaceRolePopover {...decisionTreeProps} />
      </Select>
    </CellWrapper>
  );
};

export default WorkspaceRoleCell;
