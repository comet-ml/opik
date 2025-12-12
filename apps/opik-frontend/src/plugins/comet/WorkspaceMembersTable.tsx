import React, { useMemo } from "react";
import {
  ColumnDef,
  OnChangeFn,
  ColumnSizingState,
} from "@tanstack/react-table";
import useUser from "@/plugins/comet/useUser";
import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import useAllWorkspaceMembers from "@/plugins/comet/useWorkspaceMembers";
import useWorkspaceUsersPermissions from "@/plugins/comet/api/useWorkspaceUsersPermissions";
import useAppStore from "@/store/AppStore";
import DataTable from "@/components/shared/DataTable/DataTable";
import Loader from "@/components/shared/Loader/Loader";
import { getPermissionByType, isUserPermissionValid } from "@/lib/permissions";
import { ManagementPermissionsNames } from "./types";

interface WorkspaceMember {
  id: string;
  userName: string;
  email: string;
  joinedAt?: string;
  role?: string;
}

export interface WorkspaceMembersTableProps {
  columns: ColumnDef<WorkspaceMember, WorkspaceMember>[];
  resizeConfig?: {
    enabled: boolean;
    columnSizing?: ColumnSizingState;
    onColumnResize?: OnChangeFn<ColumnSizingState>;
  };
  search?: string;
}

const WorkspaceMembersTable: React.FC<WorkspaceMembersTableProps> = ({
  columns,
  resizeConfig,
  search = "",
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: user } = useUser();
  const { data: allWorkspaces } = useAllWorkspaces({
    enabled: !!user?.loggedIn,
  });

  const workspace = allWorkspaces?.find(
    (w) => w.workspaceName === workspaceName,
  );

  const { data: workspaceMembers, isPending } = useAllWorkspaceMembers(
    { workspaceId: workspace?.workspaceId || "" },
    {
      enabled: Boolean(workspace?.workspaceId),
    },
  );

  const { data: permissionsData, isPending: isPermissionsPending } =
    useWorkspaceUsersPermissions(
      { workspaceId: workspace?.workspaceId || "" },
      {
        enabled: Boolean(workspace?.workspaceId),
      },
    );

  const tableData = useMemo(() => {
    if (!workspaceMembers) return [];

    const searchLower = search.toLowerCase();

    const filteredMembers = search
      ? workspaceMembers.filter((member) => {
          return (
            member.userName.toLowerCase().includes(searchLower) ||
            member.email.toLowerCase().includes(searchLower)
          );
        })
      : workspaceMembers;

    return filteredMembers.map((member): WorkspaceMember => {
      const userPermissions = permissionsData?.find(
        (permission) => permission.userName === member.userName,
      )?.permissions;

      const permissionByType = getPermissionByType(
        userPermissions,
        ManagementPermissionsNames.MANAGEMENT,
      );

      const role = isUserPermissionValid(permissionByType?.permissionValue)
        ? "Workspace owner"
        : "Workspace member";

      return {
        id: member.userName,
        role,
        ...member,
      };
    });
  }, [workspaceMembers, permissionsData, search]);

  if (isPending || isPermissionsPending) {
    return <Loader />;
  }

  return (
    <DataTable columns={columns} data={tableData} resizeConfig={resizeConfig} />
  );
};

export default WorkspaceMembersTable;
