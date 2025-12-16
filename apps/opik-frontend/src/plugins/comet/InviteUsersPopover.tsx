import React, { useState } from "react";
import { DropdownMenuContent } from "@/components/ui/dropdown-menu";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import Loader from "@/components/shared/Loader/Loader";
import useUsernameAutocomplete from "./api/useUsernameAutocomplete";
import useCurrentOrganization from "./useCurrentOrganization";
import useWorkspace from "./useWorkspace";

const InviteUsersPopover = () => {
  const [searchQuery, setSearchQuery] = useState("");

  const workspace = useWorkspace();
  const workspaceId = workspace?.workspaceId;
  const workspaceName = workspace?.workspaceName;

  const currentOrganization = useCurrentOrganization();
  const organizationId = currentOrganization?.id || "";

  const { data: users = [], isPending: isUsersPending } =
    useUsernameAutocomplete(
      {
        query: searchQuery,
        organizationId,
        excludedWorkspaceId: workspaceId || "",
      },
      {
        enabled: Boolean(searchQuery && organizationId),
      },
    );

  const renderUserList = () => {
    if (!searchQuery) {
      return (
        <div className="comet-body-s flex h-32 items-center justify-center text-muted-slate">
          Start typing to search for users
        </div>
      );
    }

    if (isUsersPending) {
      return (
        <div className="flex h-32 items-center justify-center">
          <Loader />
        </div>
      );
    }

    if (users.length === 0) {
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
            className="flex cursor-pointer items-center gap-3 rounded-sm px-3 py-2.5 transition-colors hover:bg-primary-foreground"
          >
            <div className="flex flex-1 flex-col">
              <span className="comet-body-s-accented">{user}</span>
            </div>
          </div>
        ))}
      </div>
    );
  };

  return (
    <DropdownMenuContent align="start" className="w-[400px] p-4">
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
