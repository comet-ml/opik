import React, { useMemo } from "react";
import { ColumnDef } from "@tanstack/react-table";
import useUser from "@/plugins/comet/useUser";
import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import useAllWorkspaceMembers from "@/plugins/comet/useWorkspaceMembers";
import useAppStore from "@/store/AppStore";
import DataTable from "@/components/shared/DataTable/DataTable";
import Loader from "@/components/shared/Loader/Loader";

interface WorkspaceMember {
  id: string;
  userName: string;
  email: string;
  joinedAt?: string;
}

export interface WorkspaceMembersTableProps {
  columns: ColumnDef<WorkspaceMember, WorkspaceMember>[];
}

const WorkspaceMembersTable: React.FC<WorkspaceMembersTableProps> = ({
  columns,
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

  const tableData = useMemo(() => {
    if (!workspaceMembers) return [];

    return workspaceMembers.map(
      (member): WorkspaceMember => ({
        id: member.userName,
        ...member,
      }),
    );
  }, [workspaceMembers]);

  if (isPending) {
    return <Loader />;
  }

  return <DataTable columns={columns} data={tableData} />;
};

export default WorkspaceMembersTable;
