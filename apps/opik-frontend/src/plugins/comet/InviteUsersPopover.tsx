import React, { useMemo } from "react";
import { Mail } from "lucide-react";
import useCurrentOrganization from "@/plugins/comet/useCurrentOrganization";
import useWorkspace from "@/plugins/comet/useWorkspace";
import useUsernameAutocomplete from "@/plugins/comet/api/useUsernameAutocomplete";
import { useInviteUsersMutation } from "@/plugins/comet/api/useInviteMembersMutation";
import useAllWorkspaceMembers from "@/plugins/comet/useWorkspaceMembers";
import useWorkspaceUsersPermissions from "@/plugins/comet/api/useWorkspaceUsersPermissions";
import {
  getPermissionByType,
  isUserPermissionValid,
} from "@/plugins/comet/lib/permissions";
import {
  ManagementPermissionsNames,
  WORKSPACE_ROLE_TYPE,
} from "@/plugins/comet/types";
import {
  DropdownMenuContent,
  DropdownMenuSubContent,
} from "@/components/ui/dropdown-menu";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { Tag } from "@/components/ui/tag";
import { cn } from "@/lib/utils";

const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const MIN_USERNAME_LENGTH = 3;
interface InviteUsersPopoverProps {
  searchQuery: string;
  setSearchQuery: (query: string) => void;
  onClose: () => void;
  side?: "top" | "right" | "bottom" | "left";
  asSubContent?: boolean;
}

const InviteUsersPopover: React.FC<InviteUsersPopoverProps> = ({
  searchQuery,
  setSearchQuery,
  onClose,
  side = "bottom",
  asSubContent = false,
}) => {
  const workspace = useWorkspace();
  const workspaceId = workspace?.workspaceId;
  const workspaceName = workspace?.workspaceName;

  const currentOrganization = useCurrentOrganization();
  const organizationId = currentOrganization?.id || "";

  const { data: users = [], isLoading } = useUsernameAutocomplete(
    {
      query: searchQuery,
      organizationId,
    },
    {
      enabled:
        Boolean(searchQuery && organizationId) &&
        searchQuery.length >= MIN_USERNAME_LENGTH,
    },
  );

  const { data: workspaceMembers = [] } = useAllWorkspaceMembers(
    { workspaceId: workspaceId || "" },
    {
      enabled: Boolean(workspaceId),
    },
  );

  const { data: permissionsData = [] } = useWorkspaceUsersPermissions(
    { workspaceId: workspaceId || "" },
    {
      enabled: Boolean(workspaceId),
    },
  );

  const inviteUsersMutation = useInviteUsersMutation();

  const userRoleMap = useMemo(() => {
    const permissionsMap = new Map(
      permissionsData.map((permission) => [
        permission.userName,
        permission.permissions,
      ]),
    );

    const map = new Map<string, WORKSPACE_ROLE_TYPE>();
    workspaceMembers.forEach((member) => {
      const userPermissions =
        member.userName && permissionsMap.get(member.userName)
          ? permissionsMap.get(member.userName) || []
          : [];

      const permissionByType = getPermissionByType(
        userPermissions,
        ManagementPermissionsNames.MANAGEMENT,
      );

      const role = isUserPermissionValid(permissionByType?.permissionValue)
        ? WORKSPACE_ROLE_TYPE.owner
        : WORKSPACE_ROLE_TYPE.member;

      if (member.userName) {
        map.set(member.userName, role);
      }
      if (member.email) {
        map.set(member.email.toLowerCase(), role);
      }
    });
    return map;
  }, [workspaceMembers, permissionsData]);

  const getUserRole = (identifier: string): WORKSPACE_ROLE_TYPE | null => {
    return userRoleMap.get(identifier.toLowerCase()) || null;
  };

  const hasEmailQuery = EMAIL_REGEX.test(searchQuery);

  const handleInviteUser = (userName?: string) => {
    if (!workspaceId) return;

    const userToInvite = userName ?? searchQuery;

    inviteUsersMutation.mutate(
      {
        workspaceId,
        users: [userToInvite],
      },
      {
        onSuccess: () => {
          setSearchQuery("");
          onClose();
        },
      },
    );
  };

  const renderUserList = () => {
    const isQueryTooShort =
      searchQuery.length < MIN_USERNAME_LENGTH && !hasEmailQuery;

    if (!searchQuery || isQueryTooShort) {
      return (
        <div className="comet-body-s flex h-full items-center justify-center text-muted-slate">
          Type at least {MIN_USERNAME_LENGTH} characters or enter an email
          address
        </div>
      );
    }

    const hasResults = users.length > 0;
    const showEmailRow = hasEmailQuery;

    if (!hasResults && !showEmailRow && !isLoading) {
      return (
        <div className="comet-body-s flex h-full items-center justify-center text-muted-slate">
          No users found. You can also invite by email address
        </div>
      );
    }

    const emailRole = showEmailRow ? getUserRole(searchQuery) : null;

    return (
      <div className="h-full space-y-1 overflow-y-auto">
        {users.map((user) => {
          const role = getUserRole(user);
          return (
            <div
              key={user}
              onClick={() => !role && handleInviteUser(user)}
              className={cn(
                "flex items-center gap-3 rounded-sm px-3 py-2.5 transition-colors",
                role
                  ? "cursor-not-allowed opacity-50"
                  : "cursor-pointer hover:bg-primary-foreground",
              )}
            >
              <div className="flex flex-1 flex-col">
                <span className="comet-body-s-accented">{user}</span>
              </div>
              {role && (
                <Tag variant="gray" size="sm">
                  {role}
                </Tag>
              )}
            </div>
          );
        })}
        {showEmailRow && (
          <div
            onClick={() => !emailRole && handleInviteUser()}
            className={cn(
              "flex items-center gap-3 rounded-sm px-3 py-2.5 transition-colors",
              emailRole
                ? "cursor-not-allowed opacity-50"
                : "cursor-pointer hover:bg-primary-foreground",
            )}
          >
            <div className="flex flex-1 flex-col">
              <span className="comet-body-s-accented">{searchQuery}</span>
            </div>
            {emailRole ? (
              <Tag variant="gray" size="sm">
                {emailRole}
              </Tag>
            ) : (
              <Mail className="size-4 shrink-0 text-muted-slate" />
            )}
          </div>
        )}
      </div>
    );
  };

  const content = (
    <>
      <div className="mb-3">
        <h3 className="comet-title-s">
          Invite to {workspaceName || "workspace"}
        </h3>
      </div>
      <div className="space-y-3">
        <SearchInput
          searchText={searchQuery}
          setSearchText={setSearchQuery}
          placeholder="Search by username or email"
        />
        <div className="h-[200px]">{renderUserList()}</div>
      </div>
    </>
  );

  if (asSubContent) {
    return (
      <DropdownMenuSubContent className="w-[400px] p-4">
        {content}
      </DropdownMenuSubContent>
    );
  }

  return (
    <DropdownMenuContent
      side={side}
      align="end"
      alignOffset={-16}
      collisionPadding={{ bottom: 16 }}
      className="w-[400px] p-4"
    >
      {content}
    </DropdownMenuContent>
  );
};

export default InviteUsersPopover;
