import React, { useMemo } from "react";
import useAppStore from "@/store/AppStore";
import DataTable from "@/components/shared/DataTable/DataTable";
import Loader from "@/components/shared/Loader/Loader";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import { formatDate } from "@/lib/date";
import useUser from "@/plugins/comet/useUser";
import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import useAllWorkspaceMembers from "@/plugins/comet/useWorkspaceMembers";

interface WorkspaceMember {
  id: string;
  userName: string;
  email: string;
  joinedAt?: string;
}

const DEFAULT_COLUMNS: ColumnData<WorkspaceMember>[] = [
  {
    id: "userName",
    label: "Name / User name",
    type: COLUMN_TYPE.string,
  },
  {
    id: "email",
    label: "Email",
    type: COLUMN_TYPE.string,
  },
  {
    id: "joinedAt",
    label: "Joined",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => {
      if (!row.joinedAt) return "-";
      const dateString = new Date(row.joinedAt).toISOString();
      return formatDate(dateString);
    },
  },
  {
    id: "role",
    label: "Workspace role",
    type: COLUMN_TYPE.category,
  },
];

const CollaboratorsTab = () => {
  const { data: user } = useUser();
  const { data: allWorkspaces } = useAllWorkspaces({
    enabled: !!user?.loggedIn,
  });

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const workspace = allWorkspaces?.find(
    (w) => w.workspaceName === workspaceName,
  );

  const { data: workspaceMembers, isPending } = useAllWorkspaceMembers(
    { workspaceId: workspace?.workspaceId || "" },
    {
      enabled: Boolean(workspace?.workspaceId),
    },
  );

  const columns = useMemo(() => {
    return convertColumnDataToColumn<WorkspaceMember, WorkspaceMember>(
      DEFAULT_COLUMNS,
      {},
    );
  }, []);

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

export default CollaboratorsTab;
