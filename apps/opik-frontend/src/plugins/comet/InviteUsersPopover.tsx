import React from "react";
import { Mail } from "lucide-react";
import useCurrentOrganization from "@/plugins/comet/useCurrentOrganization";
import useWorkspace from "@/plugins/comet/useWorkspace";
import { DropdownMenuContent } from "@/components/ui/dropdown-menu";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import useUsernameAutocomplete from "./api/useUsernameAutocomplete";
import { useInviteEmailMutation } from "./api/useInviteEmailMutation";
import { useInviteUsernameMutation } from "./api/useInviteUsernameMutation";

const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const MIN_USERNAME_LENGTH = 3;
interface InviteUsersPopoverProps {
  searchQuery: string;
  setSearchQuery: (query: string) => void;
  onClose: () => void;
  side?: "top" | "right" | "bottom" | "left";
}

const InviteUsersPopover: React.FC<InviteUsersPopoverProps> = ({
  searchQuery,
  setSearchQuery,
  onClose,
  side = "bottom",
}) => {
  const workspace = useWorkspace();
  const workspaceId = workspace?.workspaceId;
  const workspaceName = workspace?.workspaceName;

  const currentOrganization = useCurrentOrganization();
  const organizationId = currentOrganization?.id || "";

  const { data: users = [] } = useUsernameAutocomplete(
    {
      query: searchQuery,
      organizationId,
      excludedWorkspaceId: workspaceId || "",
    },
    {
      enabled:
        Boolean(searchQuery && organizationId) &&
        searchQuery.length >= MIN_USERNAME_LENGTH,
    },
  );

  const inviteEmailMutation = useInviteEmailMutation();
  const inviteUsernameMutation = useInviteUsernameMutation();

  const hasEmailQuery = EMAIL_REGEX.test(searchQuery);

  const handleEmailClick = () => {
    if (!workspaceId || !hasEmailQuery) return;

    inviteEmailMutation.mutate(
      {
        workspaceId,
        email: searchQuery,
      },
      {
        onSuccess: () => {
          setSearchQuery("");
          onClose();
        },
      },
    );
  };

  const handleUsernameClick = (userName: string) => {
    if (!workspaceId) return;

    inviteUsernameMutation.mutate(
      {
        workspaceId,
        userName,
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
    if (!searchQuery) {
      return (
        <div className="comet-body-s flex h-32 items-center justify-center text-muted-slate">
          Start typing to search for users
        </div>
      );
    }

    const hasResults = users.length > 0;
    const showEmailRow = hasEmailQuery;

    if (!hasResults && !showEmailRow) {
      return (
        <div className="comet-body-s flex h-32 items-center justify-center text-muted-slate">
          No users found
        </div>
      );
    }

    return (
      <div className="max-h-[300px] space-y-1 overflow-y-auto">
        {users.map((user) => (
          <div
            key={user}
            onClick={() => handleUsernameClick(user)}
            className="flex cursor-pointer items-center gap-3 rounded-sm px-3 py-2.5 transition-colors hover:bg-primary-foreground"
          >
            <div className="flex flex-1 flex-col">
              <span className="comet-body-s-accented">{user}</span>
            </div>
          </div>
        ))}
        {showEmailRow && (
          <div
            onClick={handleEmailClick}
            className="flex cursor-pointer items-center gap-3 rounded-sm px-3 py-2.5 transition-colors hover:bg-primary-foreground"
          >
            <div className="flex flex-1 flex-col">
              <span className="comet-body-s-accented">{searchQuery}</span>
            </div>
            <Mail className="size-4 shrink-0 text-muted-slate" />
          </div>
        )}
      </div>
    );
  };

  return (
    <DropdownMenuContent side={side} align="start" className="w-[400px] p-4">
      <div className="mb-3">
        <h3 className="comet-title-s">
          Invite to {workspaceName || "workspace"}
        </h3>
      </div>
      <div className="space-y-3">
        <SearchInput
          searchText={searchQuery}
          setSearchText={setSearchQuery}
          placeholder="Search"
        />
        {renderUserList()}
      </div>
    </DropdownMenuContent>
  );
};

export default InviteUsersPopover;
