import { useMemo, useState } from "react";
import useLocalStorageState from "use-local-storage-state";
import { UserPlus } from "lucide-react";
import useAllWorkspaceMembers from "@/plugins/comet/useWorkspaceMembers";
import useWorkspaceUsersPermissions from "@/plugins/comet/api/useWorkspaceUsersPermissions";
import useOrganizationMembers from "@/plugins/comet/api/useOrganizationMembers";
import useCurrentOrganization from "@/plugins/comet/useCurrentOrganization";
import useWorkspace from "@/plugins/comet/useWorkspace";
import useWorkspaceEmailInvites from "@/plugins/comet/useWorkspaceEmailInvites";
import useUserPermission from "@/plugins/comet/useUserPermission";
import DataTable from "@/components/shared/DataTable/DataTable";
import ExplainerCallout from "@/components/shared/ExplainerCallout/ExplainerCallout";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import Loader from "@/components/shared/Loader/Loader";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import InviteUsersPopover from "./InviteUsersPopover";
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
import WorkspaceMemberActionsCell from "./WorkspaceMemberActionsCell";
import { generateActionsColumDef } from "@/components/shared/DataTable/utils";

const COLUMNS_WIDTH_KEY = "workspace-members-columns-width";

const DEFAULT_COLUMNS: ColumnData<WorkspaceMember>[] = [
  {
    id: "userName",
    label: "Name / User name",
    type: COLUMN_TYPE.string,
    accessorFn: (row) => row.userName || "-",
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
  const [inviteSearchQuery, setInviteSearchQuery] = useState("");
  const [isInvitePopoverOpen, setIsInvitePopoverOpen] = useState(false);

  const workspace = useWorkspace();
  const workspaceId = workspace?.workspaceId;

  const currentOrganization = useCurrentOrganization();
  const { isWorkspaceOwner } = useUserPermission();

  const { data: workspaceMembers = [], isPending } = useAllWorkspaceMembers(
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

  const { data: invitedMembers = [], isPending: isInvitedMembersPending } =
    useWorkspaceEmailInvites(
      { workspaceId: workspaceId || "" },
      {
        enabled: Boolean(workspaceId),
      },
    );

  const columns = useMemo(() => {
    return [
      ...convertColumnDataToColumn<WorkspaceMember, WorkspaceMember>(
        DEFAULT_COLUMNS,
        {},
      ),
      generateActionsColumDef({
        cell: WorkspaceMemberActionsCell,
      }),
    ];
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
    const allUsers = [...workspaceMembers, ...invitedMembers];

    const searchLower = search.toLowerCase();

    const mappedMembers = allUsers.map((member): WorkspaceMember => {
      const userName = (member as WorkspaceMember).userName;
      const userPermissions = permissionsData?.find(
        (permission) => permission.userName === userName,
      )?.permissions;

      const permissionByType = getPermissionByType(
        userPermissions || [],
        ManagementPermissionsNames.MANAGEMENT,
      );

      const role = isUserPermissionValid(permissionByType?.permissionValue)
        ? WORKSPACE_ROLE_TYPE.owner
        : WORKSPACE_ROLE_TYPE.member;

      const uniqueName = userName || member.email;

      const memberInOrganization = organizationMembers?.find(
        (memberInOrg) =>
          (memberInOrg?.userName || memberInOrg?.email) === uniqueName,
      );

      return {
        id: uniqueName,
        role,
        isAdmin: memberInOrganization?.role === ORGANIZATION_ROLE_TYPE.admin,
        permissions: userPermissions || [],
        ...member,
      };
    });

    return search
      ? mappedMembers.filter((member) => {
          return (
            member.userName?.toLowerCase().includes(searchLower) ||
            member.email.toLowerCase().includes(searchLower) ||
            member.role?.toLowerCase().includes(searchLower)
          );
        })
      : mappedMembers;
  }, [
    workspaceMembers,
    invitedMembers,
    permissionsData,
    organizationMembers,
    search,
  ]);

  const renderTable = () => {
    if (isPending || isPermissionsPending || isInvitedMembersPending) {
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

  if (!isWorkspaceOwner) {
    return null;
  }

  return (
    <>
      <ExplainerCallout
        className="mb-4"
        {...EXPLAINERS_MAP[EXPLAINER_ID.why_do_i_need_the_collaborators_tab]}
      />
      <div className="mb-4 flex items-center justify-between">
        <SearchInput
          searchText={search}
          setSearchText={setSearch}
          placeholder="Search by name, email, or role"
          className="w-[320px]"
          dimension="sm"
        />
        <DropdownMenu
          open={isInvitePopoverOpen}
          onOpenChange={(open) => {
            setIsInvitePopoverOpen(open);
            if (!open) {
              setInviteSearchQuery("");
            }
          }}
        >
          <DropdownMenuTrigger asChild>
            <Button variant="default" size="sm">
              <UserPlus className="mr-1.5 size-3.5" />
              Add users
            </Button>
          </DropdownMenuTrigger>
          <InviteUsersPopover
            searchQuery={inviteSearchQuery}
            setSearchQuery={setInviteSearchQuery}
            onClose={() => setIsInvitePopoverOpen(false)}
          />
        </DropdownMenu>
      </div>
      {renderTable()}
    </>
  );
};

export default CollaboratorsTab;
