import { useMemo, useState } from "react";
import useLocalStorageState from "use-local-storage-state";
import usePluginsStore from "@/store/PluginsStore";
import DataTable from "@/components/shared/DataTable/DataTable";
import ExplainerCallout from "@/components/shared/ExplainerCallout/ExplainerCallout";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import { formatDate } from "@/lib/date";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

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

  const [search, setSearch] = useState("");

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

  const renderTable = () => {
    if (WorkspaceMembersTable) {
      return (
        <WorkspaceMembersTable
          columns={columns}
          resizeConfig={resizeConfig}
          search={search}
        />
      );
    }
    return (
      <DataTable columns={columns} data={[]} resizeConfig={resizeConfig} />
    );
  };

  return (
    <>
      <ExplainerCallout
        className="mb-4"
        {...EXPLAINERS_MAP[EXPLAINER_ID.why_do_i_need_the_collaborators_tab]}
      />
      <SearchInput
        searchText={search}
        setSearchText={setSearch}
        placeholder="Search by name or email"
        className="mb-4 w-[320px]"
        dimension="sm"
      />
      {renderTable()}
    </>
  );
};

export default CollaboratorsTab;
