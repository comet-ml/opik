import React, { useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { ChevronDown } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import useManageUsersRolePopover from "@/hooks/useManageUsersRolePopover";
import useUserPermission from "@/plugins/comet/useUserPermission";
import { UserPermission, WorkspaceMember } from "@/plugins/comet/types";
import useAppStore, { useLoggedInUserName } from "@/store/AppStore";
import { getKeyForChangingRole } from "@/lib/permissions";
import WorkspaceRolePopover from "@/plugins/comet/WorkspaceRolePopover";

interface PopoverData extends WorkspaceMember {
  avatar?: string;
  firstName?: string | null;
  gitHub?: boolean;
  lastActivityAt?: string | null;
  lastName?: string | null;
  userId?: string;
}

const WorkspaceRoleCell = (context: CellContext<WorkspaceMember, string>) => {
  const value = context.getValue();
  const row = context.row.original;

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const currentUserName = useLoggedInUserName();

  const { getPermissionStatus } = useUserPermission();

  const [popoverData, setPopoverData] = useState<PopoverData | null>(null);

  const ifChangeWsRoleDisabled = !getPermissionStatus({
    workspaceName,
    permissionKey: getKeyForChangingRole(
      currentUserName!,
      popoverData?.userName || popoverData?.email || "",
    ),
  });

  const setPermissions = (newPermissions: UserPermission[]) => {
    setPopoverData((data) => ({
      ...data!,
      permissions: newPermissions,
    }));
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
