import { useMemo } from "react";
import usePluginsStore from "@/store/PluginsStore";
import DataTable from "@/components/shared/DataTable/DataTable";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import { formatDate } from "@/lib/date";

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
  const WorkspaceMembersTable = usePluginsStore(
    (state) => state.WorkspaceMembersTable,
  );

  const columns = useMemo(() => {
    return convertColumnDataToColumn<WorkspaceMember, WorkspaceMember>(
      DEFAULT_COLUMNS,
      {},
    );
  }, []);

  if (WorkspaceMembersTable) {
    return <WorkspaceMembersTable columns={columns} />;
  }

  return <DataTable columns={columns} data={[]} />;
};

export default CollaboratorsTab;
