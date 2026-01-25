import React, { useState, useMemo } from "react";
import { Link, useNavigate } from "@tanstack/react-router";
import { ChevronsUpDown, Settings } from "lucide-react";
import sortBy from "lodash/sortBy";
import toLower from "lodash/toLower";

import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Separator } from "@/components/ui/separator";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { cn, calculateWorkspaceName } from "@/lib/utils";
import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import useCurrentOrganization from "@/plugins/comet/useCurrentOrganization";
import useUser from "@/plugins/comet/useUser";
import useAppStore from "@/store/AppStore";
import { Workspace } from "@/plugins/comet/types";
import { DEFAULT_WORKSPACE_NAME } from "@/constants/user";

const WorkspaceSelector: React.FC = () => {
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const [isDropdownOpen, setIsDropdownOpen] = useState(false);
  const { data: user } = useUser();
  const { data: allWorkspaces, isLoading } = useAllWorkspaces({
    enabled: !!user?.loggedIn,
  });
  const currentOrganization = useCurrentOrganization();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const handleChangeWorkspace = (newWorkspace: Workspace) => {
    navigate({
      to: "/$workspaceName",
      params: { workspaceName: newWorkspace.workspaceName },
    });
  };

  // Filter workspaces by current organization (before early return)
  const organizationWorkspaces = useMemo(() => {
    if (!allWorkspaces || !currentOrganization) return [];
    return allWorkspaces.filter(
      (workspace) => workspace.organizationId === currentOrganization.id,
    );
  }, [allWorkspaces, currentOrganization]);

  // Filter out default workspace from selectable options
  const selectableWorkspaces = useMemo(() => {
    return organizationWorkspaces.filter(
      (workspace) => workspace.workspaceName !== DEFAULT_WORKSPACE_NAME,
    );
  }, [organizationWorkspaces]);

  // Filter workspaces by search query
  const filteredWorkspaces = useMemo(() => {
    if (!search) return selectableWorkspaces;
    const searchLower = toLower(search);
    return selectableWorkspaces.filter((workspace) => {
      const displayName = calculateWorkspaceName(workspace.workspaceName);
      return toLower(displayName).includes(searchLower);
    });
  }, [selectableWorkspaces, search]);

  if (!user?.loggedIn || isLoading || !allWorkspaces || !currentOrganization) {
    // Show minimal placeholder while loading
    return (
      <div className="flex h-8 items-center">
        <div className="h-4 w-24 animate-pulse rounded bg-muted" />
      </div>
    );
  }

  // Get current workspace display name
  const currentWorkspaceDisplayName = calculateWorkspaceName(workspaceName);

  // Determine if we should show dropdown
  // Allow opening dropdown even with one workspace, but not if current workspace is Personal (default)
  const isCurrentWorkspaceDefault = workspaceName === DEFAULT_WORKSPACE_NAME;
  const hasSelectableWorkspaces = selectableWorkspaces.length > 0;
  const shouldShowDropdown =
    hasSelectableWorkspaces && !isCurrentWorkspaceDefault;

  const handleOpenChange = (open: boolean) => {
    setIsDropdownOpen(open);
    if (!open) {
      setSearch("");
    }
  };

  const handleManageWorkspaces = () => {
    setIsDropdownOpen(false);
    navigate({
      to: "/$workspaceName/configuration",
      params: { workspaceName },
    });
  };

  if (!shouldShowDropdown) {
    // Single workspace (and not default) - just show it as a link
    return (
      <div className="-mr-1.5 flex h-8 items-center gap-1.5 px-1.5">
        <Link
          to="/$workspaceName/home"
          params={{ workspaceName }}
          className="comet-body-s min-w-0 flex-1 truncate text-left hover:underline"
        >
          {currentWorkspaceDisplayName}
        </Link>
      </div>
    );
  }

  // Multiple workspaces - show name as link and chevron as dropdown trigger
  const nameLink = (
    <Link
      to="/$workspaceName/home"
      params={{ workspaceName }}
      className="comet-body-s min-w-0 flex-1 truncate text-left hover:underline"
    >
      {currentWorkspaceDisplayName}
    </Link>
  );

  const chevronButton = (
    <button
      className={cn(
        "flex items-center justify-center rounded-md text-foreground transition-colors hover:bg-primary-foreground",
        "h-8 w-6 shrink-0 px-0",
      )}
      onClick={(e) => {
        e.preventDefault();
        e.stopPropagation();
      }}
    >
      <ChevronsUpDown className="size-4 shrink-0 text-muted-slate" />
    </button>
  );

  return (
    <div className="-mr-1.5 flex h-8 items-center gap-1 px-1.5">
      {nameLink}
      <DropdownMenu open={isDropdownOpen} onOpenChange={handleOpenChange}>
        <DropdownMenuTrigger asChild>{chevronButton}</DropdownMenuTrigger>
        <DropdownMenuContent className="w-60 p-1 pt-12" align="start">
          <div className="absolute inset-x-1 top-1 h-11">
            <SearchInput
              searchText={search}
              setSearchText={setSearch}
              placeholder="Find workspace"
              variant="ghost"
            />
            <Separator className="mt-1" />
          </div>
          <div className="max-h-[200px] overflow-auto">
            {filteredWorkspaces.length > 0 ? (
              sortBy(filteredWorkspaces, "workspaceName").map((workspace) => {
                const displayName = calculateWorkspaceName(
                  workspace.workspaceName,
                );
                const isSelected = workspace.workspaceName === workspaceName;
                return (
                  <DropdownMenuCheckboxItem
                    checked={isSelected}
                    key={workspace.workspaceName}
                    onClick={() => handleChangeWorkspace(workspace)}
                  >
                    <TooltipWrapper content={displayName}>
                      <span className="truncate">{displayName}</span>
                    </TooltipWrapper>
                  </DropdownMenuCheckboxItem>
                );
              })
            ) : (
              <div className="flex min-h-[120px] flex-col items-center justify-center px-4 py-2 text-center">
                <div className="comet-body-s text-muted-slate">
                  No workspaces found
                </div>
              </div>
            )}
          </div>
          <div className="sticky inset-x-0 bottom-0">
            <Separator className="my-1" />
            <div
              className="relative flex h-10 cursor-pointer items-center rounded-md pl-8 pr-2 hover:bg-primary-foreground"
              onClick={handleManageWorkspaces}
            >
              <span className="absolute left-2 flex size-3.5 items-center justify-center">
                <Settings className="size-3.5 shrink-0 text-primary" />
              </span>
              <span className="comet-body-s text-primary">
                Manage workspaces
              </span>
            </div>
          </div>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
};

export default WorkspaceSelector;
