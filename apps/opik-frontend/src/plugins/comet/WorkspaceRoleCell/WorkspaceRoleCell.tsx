import React, { useState, useMemo, useEffect, useRef } from "react";
import { CellContext } from "@tanstack/react-table";
import debounce from "lodash/debounce";
import { Select, SelectTrigger, SelectValue } from "@/components/ui/select";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import useUserPermission from "@/plugins/comet/useUserPermission";
import { getKeyForChangingRole } from "@/plugins/comet/lib/permissions";
import { UserPermission, WorkspaceMember } from "@/plugins/comet/types";
import WorkspaceRolePopover from "@/plugins/comet/WorkspaceRolePopover";
import WorkspaceRolesSelectContent from "./WorkspaceRolesSelectContent";
import useWorkspace from "@/plugins/comet/useWorkspace";
import { useUpdateWorkspaceUsersPermissionsMutation } from "@/plugins/comet/api/useUpdateWorkspaceUsersPermissionsMutation";
import { useUpdateWorkspaceUserRoleMutation } from "@/plugins/comet/api/useUpdateWorkspaceUserRoleMutation";
import { useLoggedInUserName } from "@/store/AppStore";
import useManageUsersRolePopover from "./useManageUsersRolePopover";
import { useWorkspaceRolesContext } from "@/plugins/comet/WorkspaceRolesContext";
import useCurrentOrganization from "@/plugins/comet/useCurrentOrganization";

const WorkspaceRoleCell = (context: CellContext<WorkspaceMember, string>) => {
  const value = context.getValue();
  const row = context.row.original;
  const isInvitedByEmail = !row.isMember;
  const isOrganizationAdmin = row.isAdmin;

  const currentUserName = useLoggedInUserName();

  const { getPermissionStatus } = useUserPermission();

  const workspace = useWorkspace();

  const { roles: workspaceRoles } = useWorkspaceRolesContext();

  const currentOrganization = useCurrentOrganization();
  const isPermissionsManagementEnabled =
    currentOrganization?.workspaceRolesEnabled ?? false;

  const [popoverData, setPopoverData] = useState<WorkspaceMember | null>(null);
  const [isSelectOpen, setIsSelectOpen] = useState(false);
  const shouldKeepOpenRef = useRef(false);

  const { mutate: updateWorkspaceUsersPermissions } =
    useUpdateWorkspaceUsersPermissionsMutation();

  const { mutate: updateWorkspaceUserRole } =
    useUpdateWorkspaceUserRoleMutation();

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

  const isRoleChangeDisabled = isInvitedByEmail || isOrganizationAdmin;

  const trigger = (
    <SelectTrigger
      className="-ml-1 h-auto border-none bg-transparent px-1 py-0.5 disabled:bg-transparent [&>span]:block [&>span]:truncate"
      onClick={(e) => {
        e.stopPropagation();
        if (isRoleChangeDisabled) {
          e.preventDefault();
        }
      }}
      disabled={isRoleChangeDisabled}
    >
      <SelectValue placeholder={value}>{value}</SelectValue>
    </SelectTrigger>
  );

  const handleValueChange = (newValue: string) => {
    if (isPermissionsManagementEnabled) {
      const userName = row.userName || row.email;
      if (workspace?.workspaceId && userName) {
        updateWorkspaceUserRole({
          userName,
          roleId: newValue,
          workspaceId: workspace.workspaceId,
        });
      }
    } else {
      const syntheticEvent = {
        target: { value: newValue },
      } as React.ChangeEvent<HTMLInputElement>;
      decisionTreeProps.onChange(syntheticEvent);
      shouldKeepOpenRef.current = true;
    }
  };

  const selectValue = isPermissionsManagementEnabled
    ? row.roleId || ""
    : decisionTreeProps.controlValue || "";

  const getTooltipContent = () => {
    if (isOrganizationAdmin) {
      return "Cannot change workspace role for organization admins";
    }
    if (isInvitedByEmail) {
      return "Cannot change roles for users invited by email";
    }
    return "";
  };

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <Select
        value={selectValue}
        open={isSelectOpen}
        onOpenChange={(open) => {
          if (!open && shouldKeepOpenRef.current) {
            shouldKeepOpenRef.current = false;
            return;
          }
          setIsSelectOpen(open);
          if (open && !isRoleChangeDisabled) {
            setPopoverData(row);
          }
        }}
        onValueChange={handleValueChange}
        disabled={isRoleChangeDisabled}
      >
        {isRoleChangeDisabled ? (
          <TooltipWrapper content={getTooltipContent()}>
            {trigger}
          </TooltipWrapper>
        ) : (
          trigger
        )}
        {isPermissionsManagementEnabled ? (
          <WorkspaceRolesSelectContent roles={workspaceRoles} />
        ) : (
          <WorkspaceRolePopover {...decisionTreeProps} />
        )}
      </Select>
    </CellWrapper>
  );
};

export default WorkspaceRoleCell;
