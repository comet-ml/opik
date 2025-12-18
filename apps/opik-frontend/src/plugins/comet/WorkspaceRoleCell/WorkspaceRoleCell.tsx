import React, { useState, useMemo, useEffect } from "react";
import { CellContext } from "@tanstack/react-table";
import { ChevronDown } from "lucide-react";
import debounce from "lodash/debounce";
import {
  DropdownMenu,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
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

  const currentUserName = useLoggedInUserName();

  const { getPermissionStatus } = useUserPermission();

  const workspace = useWorkspace();

  const [popoverData, setPopoverData] = useState<WorkspaceMember | null>(null);

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

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <DropdownMenu
        onOpenChange={(open) => {
          if (open) {
            setPopoverData(row);
          }
        }}
      >
        <DropdownMenuTrigger asChild>
          <button
            className="-ml-1 flex items-center gap-1 rounded-sm px-1 py-0.5 focus:outline-none focus:ring-2 focus:ring-offset-1"
            onClick={(e) => {
              e.stopPropagation();
            }}
          >
            <span>{value}</span>
            <ChevronDown className="size-4" />
          </button>
        </DropdownMenuTrigger>
        <WorkspaceRolePopover {...decisionTreeProps} />
      </DropdownMenu>
    </CellWrapper>
  );
};

export default WorkspaceRoleCell;
