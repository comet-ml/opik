import { useMemo, useState } from "react";
import useLocalStorageState from "use-local-storage-state";
import useAllWorkspaceMembers from "@/plugins/comet/useWorkspaceMembers";
import useWorkspaceUsersPermissions from "@/plugins/comet/api/useWorkspaceUsersPermissions";
import useOrganizationMembers from "@/plugins/comet/api/useOrganizationMembers";
import useCurrentOrganization from "@/plugins/comet/useCurrentOrganization";
import useWorkspace from "@/plugins/comet/useWorkspace";
import DataTable from "@/components/shared/DataTable/DataTable";
import ExplainerCallout from "@/components/shared/ExplainerCallout/ExplainerCallout";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import Loader from "@/components/shared/Loader/Loader";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import { formatDate } from "@/lib/date";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import {
  getPermissionByType,
  isUserPermissionValid,
} from "@/plugins/comet/lib/permissions";
import {
  ManagementPermissionsNames,
  ORGANIZATION_ROLE_TYPE,
  WORKSPACE_ROLE_TYPE,
  WorkspaceMember,
} from "./types";
import WorkspaceRoleCell from "./WorkspaceRoleCell/WorkspaceRoleCell";

const COLUMNS_WIDTH_KEY = "workspace-members-columns-width";

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
    cell: WorkspaceRoleCell as never,
  },
];

const CollaboratorsTab = () => {
  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const [search, setSearch] = useState("");

  const workspace = useWorkspace();
  const workspaceId = workspace?.workspaceId;

  const currentOrganization = useCurrentOrganization();

  const { data: workspaceMembers, isPending } = useAllWorkspaceMembers(
    { workspaceId: workspaceId || "" },
    {
      enabled: Boolean(workspaceId),
    },
  );

  const { data: permissionsData, isPending: isPermissionsPending } =
    useWorkspaceUsersPermissions(
      { workspaceId: workspaceId || "" },
      {
        enabled: Boolean(workspaceId),
      },
    );

  const { data: organizationMembers } = useOrganizationMembers({
    organizationId: currentOrganization?.id || "",
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

  const tableData = useMemo(() => {
    if (!workspaceMembers) return [];

    const searchLower = search.toLowerCase();

    const mappedMembers = workspaceMembers.map((member): WorkspaceMember => {
      const userPermissions = permissionsData?.find(
        (permission) => permission.userName === member.userName,
      )?.permissions;

      const permissionByType = getPermissionByType(
        userPermissions || [],
        ManagementPermissionsNames.MANAGEMENT,
      );

      const role = isUserPermissionValid(permissionByType?.permissionValue)
        ? WORKSPACE_ROLE_TYPE.owner
        : WORKSPACE_ROLE_TYPE.member;

      const uniqueName = member.userName || member.email;

      const memberInOrganization = organizationMembers?.find(
        (memberInOrg) =>
          (memberInOrg?.userName || memberInOrg?.email) === uniqueName,
      );

      return {
        id: member.userName,
        role,
        isAdmin: memberInOrganization?.role === ORGANIZATION_ROLE_TYPE.admin,
        permissions: userPermissions,
        ...member,
      };
    });

    return search
      ? mappedMembers.filter((member) => {
          return (
            member.userName.toLowerCase().includes(searchLower) ||
            member.email.toLowerCase().includes(searchLower) ||
            member.role?.toLowerCase().includes(searchLower)
          );
        })
      : mappedMembers;
  }, [workspaceMembers, permissionsData, organizationMembers, search]);

  const renderTable = () => {
    if (isPending || isPermissionsPending) {
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
        placeholder="Search by name, email, or role"
        className="mb-4 w-[320px]"
        dimension="sm"
      />
      {renderTable()}
    </>
  );
};

export default CollaboratorsTab;
