import { useMemo } from "react";
import useLocalStorageState from "use-local-storage-state";
import usePluginsStore from "@/store/PluginsStore";
import DataTable from "@/components/shared/DataTable/DataTable";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import { formatDate } from "@/lib/date";

const COLUMNS_WIDTH_KEY = "workspace-members-columns-width";

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

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const columns = useMemo(() => {
    return convertColumnDataToColumn<WorkspaceMember, WorkspaceMember>(
      DEFAULT_COLUMNS,
      {},
    );
  }, []);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  if (WorkspaceMembersTable) {
    return (
      <WorkspaceMembersTable columns={columns} resizeConfig={resizeConfig} />
    );
  }

  return <DataTable columns={columns} data={[]} resizeConfig={resizeConfig} />;
};

export default CollaboratorsTab;
