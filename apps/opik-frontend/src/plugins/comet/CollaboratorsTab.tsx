import { useMemo, useState } from "react";
import useLocalStorageState from "use-local-storage-state";
import useAllWorkspaceMembers from "@/plugins/comet/useWorkspaceMembers";
import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import DataTable from "@/components/shared/DataTable/DataTable";
import ExplainerCallout from "@/components/shared/ExplainerCallout/ExplainerCallout";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import Loader from "@/components/shared/Loader/Loader";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import { formatDate } from "@/lib/date";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import useAppStore from "@/store/AppStore";

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
  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const [search, setSearch] = useState("");

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: allWorkspaces } = useAllWorkspaces();

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

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
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

    return filteredMembers.map(
      (member): WorkspaceMember => ({
        id: member.userName,
        ...member,
      }),
    );
  }, [workspaceMembers, search]);

  const renderTable = () => {
    if (isPending) {
      return <Loader />;
    }

    return (
      <DataTable
        columns={columns}
        data={tableData}
        resizeConfig={resizeConfig}
      />
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
