import { useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import { UserPlus } from "lucide-react";
import useAllWorkspaceMembers from "@/plugins/comet/useWorkspaceMembers";
import useWorkspaceUsersPermissions from "@/plugins/comet/api/useWorkspaceUsersPermissions";
import useOrganizationMemberRoles from "@/plugins/comet/api/useOrganizationMemberRoles";
import useWorkspaceRoles from "@/plugins/comet/api/useWorkspaceRoles";
import useWorkspaceUsersRoles from "@/plugins/comet/api/useWorkspaceUsersRoles";
import useCurrentOrganization from "@/plugins/comet/useCurrentOrganization";
import useWorkspace from "@/plugins/comet/useWorkspace";
import useWorkspaceEmailInvites from "@/plugins/comet/useWorkspaceEmailInvites";
import useUserPermission from "@/plugins/comet/useUserPermission";
import DataTable from "@/shared/DataTable/DataTable";
import ExplainerCallout from "@/shared/ExplainerCallout/ExplainerCallout";
import SearchInput from "@/shared/SearchInput/SearchInput";
import { Button } from "@/ui/button";
import { DropdownMenu, DropdownMenuTrigger } from "@/ui/dropdown-menu";
import InviteUsersPopover from "./InviteUsersPopover";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { getUserPermissionValue } from "@/plugins/comet/lib/permissions";
import {
  ManagementPermissionsNames,
  ORGANIZATION_ROLE_TYPE,
  WORKSPACE_ROLE_TYPE,
  WorkspaceMember,
} from "./types";
import WorkspaceRoleCell from "./WorkspaceRoleCell/WorkspaceRoleCell";
import WorkspaceMemberActionsCell from "./WorkspaceMemberActionsCell";
import WorkspaceMemberWarningCell from "./WorkspaceMemberWarningCell";
import { generateActionsColumDef } from "@/shared/DataTable/utils";
import { WorkspaceRolesProvider } from "./WorkspaceRolesContext";

const COLUMNS_WIDTH_KEY = "workspace-members-columns-width";
const WARNING_COLUMN_ID = "warning";

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
      if (!row.joinedAt) return "";
      return new Date(row.joinedAt).toISOString();
    },
    cell: TimeCell as never,
  },
  {
    id: WARNING_COLUMN_ID,
    label: "Warning",
    type: COLUMN_TYPE.errors,
    cell: WorkspaceMemberWarningCell as never,
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

  const isPermissionsManagementEnabled =
    currentOrganization?.workspaceRolesEnabled ?? false;

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

  const { data: workspaceRoles = [], isPending: isWorkspaceRolesPending } =
    useWorkspaceRoles(
      { organizationId: currentOrganization?.id || "" },
      {
        enabled:
          Boolean(currentOrganization?.id) && isPermissionsManagementEnabled,
      },
    );

  const { data: workspaceUsersRoles = [], isPending: isUsersRolesPending } =
    useWorkspaceUsersRoles(
      { workspaceId: workspaceId || "" },
      {
        enabled: Boolean(workspaceId) && isPermissionsManagementEnabled,
      },
    );

  const { data: invitedMembers = [], isPending: isInvitedMembersPending } =
    useWorkspaceEmailInvites(
      { workspaceId: workspaceId || "" },
      {
        enabled: Boolean(workspaceId),
      },
    );

  const memberNames = useMemo(
    () =>
      [...workspaceMembers, ...invitedMembers]
        .map((member) => (member as WorkspaceMember).userName || member.email)
        .filter(Boolean),
    [workspaceMembers, invitedMembers],
  );

  const { data: organizationRoles, isLoading: isOrganizationRolesLoading } =
    useOrganizationMemberRoles(
      {
        organizationId: currentOrganization?.id || "",
        userNames: memberNames,
      },
      {
        enabled: Boolean(currentOrganization?.id) && memberNames.length > 0,
        // without this the table drops back to a skeleton whenever the member list changes
        placeholderData: keepPreviousData,
      },
    );

  const columns = useMemo(() => {
    const columnsToUse = isPermissionsManagementEnabled
      ? DEFAULT_COLUMNS
      : DEFAULT_COLUMNS.filter((col) => col.id !== WARNING_COLUMN_ID);

    return [
      ...convertColumnDataToColumn<WorkspaceMember, WorkspaceMember>(
        columnsToUse,
        {},
      ),
      generateActionsColumDef({
        cell: WorkspaceMemberActionsCell,
      }),
    ];
  }, [isPermissionsManagementEnabled]);

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

      const isWorkspaceOwner = !!getUserPermissionValue(
        userPermissions || [],
        ManagementPermissionsNames.MANAGEMENT,
      );

      const role = isWorkspaceOwner
        ? WORKSPACE_ROLE_TYPE.owner
        : WORKSPACE_ROLE_TYPE.member;

      const uniqueName = userName || member.email;

      const userRoleData = workspaceUsersRoles.find(
        (userRole) => userRole.userName === uniqueName,
      );

      return {
        id: uniqueName,
        role: isPermissionsManagementEnabled
          ? userRoleData?.roleName || "No role assigned"
          : role,
        roleId: userRoleData?.roleId,
        isAdmin:
          organizationRoles?.[uniqueName] === ORGANIZATION_ROLE_TYPE.admin,
        permissions: userPermissions || [],
        permissionMismatch: userRoleData?.permissionMismatch,
        ...member,
      };
    });

    return search
      ? mappedMembers.filter((member) => {
          return (
            member.userName?.toLowerCase().includes(searchLower) ||
            member.email?.toLowerCase().includes(searchLower) ||
            member.role?.toLowerCase().includes(searchLower)
          );
        })
      : mappedMembers;
  }, [
    workspaceMembers,
    invitedMembers,
    permissionsData,
    organizationRoles,
    search,
    workspaceUsersRoles,
    isPermissionsManagementEnabled,
  ]);

  const renderTable = () => {
    const isTableLoading =
      isPending ||
      isPermissionsPending ||
      isInvitedMembersPending ||
      // a row whose organization role is not known yet would offer to change an admin's role
      isOrganizationRolesLoading ||
      (isPermissionsManagementEnabled &&
        (isUsersRolesPending || isWorkspaceRolesPending));

    return (
      <WorkspaceRolesProvider
        roles={workspaceRoles}
        isLoading={isWorkspaceRolesPending}
      >
        <DataTable
          columns={columns}
          data={tableData}
          resizeConfig={resizeConfig}
          showSkeleton={isTableLoading}
        />
      </WorkspaceRolesProvider>
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
