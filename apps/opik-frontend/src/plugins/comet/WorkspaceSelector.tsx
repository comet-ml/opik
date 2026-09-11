import React from "react";
import { Link } from "@tanstack/react-router";
import { ChevronDown, ChevronUp } from "lucide-react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { calculateWorkspaceName } from "@/lib/utils";
import useWorkspaceSelectorData from "@/plugins/comet/useWorkspaceSelectorData";
import WorkspaceMenuContent from "@/plugins/comet/WorkspaceMenuContent";

const WorkspaceSelector: React.FC = () => {
  const {
    user,
    workspaceName,
    currentOrganization,
    isLoading,

    search,
    setSearch,
    isDropdownOpen,
    setIsDropdownOpen,
    isOrgSubmenuOpen,
    setIsOrgSubmenuOpen,

    handleOpenChange,
    handleChangeWorkspace,
    handleChangeOrganization,

    sortedWorkspaces,
    sortedOrganizations,

    shouldShowDropdown,
    hasMultipleOrganizations,
  } = useWorkspaceSelectorData();

  const displayName = calculateWorkspaceName(workspaceName);

  if (isLoading) {
    return (
      <div className="flex h-8 items-center">
        <div className="h-4 w-24 animate-pulse rounded bg-muted" />
      </div>
    );
  }

  if (!user?.loggedIn || !currentOrganization || !shouldShowDropdown) {
    return (
      <Link
        to="/$workspaceName/home"
        params={{ workspaceName }}
        className="comet-body-xs truncate p-1.5 transition-colors hover:text-foreground"
      >
        {displayName}
      </Link>
    );
  }

  return (
    <DropdownMenu open={isDropdownOpen} onOpenChange={handleOpenChange}>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          className="comet-body-xs flex items-center gap-1 truncate rounded p-1.5 text-muted-slate transition-colors hover:text-foreground data-[state=open]:text-foreground"
        >
          <span className="truncate">{displayName}</span>
          <span className="shrink-0">
            {isDropdownOpen ? (
              <ChevronUp className="size-3.5" />
            ) : (
              <ChevronDown className="size-3.5" />
            )}
          </span>
        </button>
      </DropdownMenuTrigger>

      <DropdownMenuContent className="w-[280px] p-1" align="start">
        <WorkspaceMenuContent
          workspaceName={workspaceName}
          currentOrganization={currentOrganization}
          sortedWorkspaces={sortedWorkspaces}
          sortedOrganizations={sortedOrganizations}
          hasMultipleOrganizations={hasMultipleOrganizations}
          isOrgSubmenuOpen={isOrgSubmenuOpen}
          setIsOrgSubmenuOpen={setIsOrgSubmenuOpen}
          setIsDropdownOpen={setIsDropdownOpen}
          handleChangeWorkspace={handleChangeWorkspace}
          handleChangeOrganization={handleChangeOrganization}
          search={search}
          setSearch={setSearch}
        />
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default WorkspaceSelector;
