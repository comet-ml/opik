import React, { useState, useMemo } from "react";
import { Link, useNavigate } from "@tanstack/react-router";
import {
  ChevronDown,
  ChevronLeft,
  ChevronUp,
  Settings,
  Settings2,
} from "lucide-react";
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
import useOrganizations from "@/plugins/comet/useOrganizations";
import useUser from "@/plugins/comet/useUser";
import useUserInvitedWorkspaces from "@/plugins/comet/useUserInvitedWorkspaces";
import useAppStore from "@/store/AppStore";
import {
  Workspace,
  Organization,
  ORGANIZATION_ROLE_TYPE,
} from "@/plugins/comet/types";
import { DEFAULT_WORKSPACE_NAME } from "@/constants/user";
import { buildUrl } from "@/plugins/comet/utils";

const WorkspaceSelector: React.FC = () => {
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const [isDropdownOpen, setIsDropdownOpen] = useState(false);
  const [isOrgSwitcherOpen, setIsOrgSwitcherOpen] = useState(false);
  const { data: user } = useUser();
  const { data: allWorkspaces, isLoading } = useAllWorkspaces({
    enabled: !!user?.loggedIn,
  });
  const { data: userInvitedWorkspaces } = useUserInvitedWorkspaces({
    enabled: !!user?.loggedIn,
  });
  const { data: organizations } = useOrganizations({
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

  const handleChangeOrganization = (newOrganization: Organization) => {
    // First try to find workspaces from userInvitedWorkspaces
    let newOrganizationWorkspaces =
      userInvitedWorkspaces?.filter(
        (workspace) => workspace.organizationId === newOrganization.id,
      ) || [];

    // If no invited workspaces found, fall back to allWorkspaces
    if (newOrganizationWorkspaces.length === 0) {
      newOrganizationWorkspaces =
        allWorkspaces?.filter(
          (workspace) => workspace.organizationId === newOrganization.id,
        ) || [];
    }

    // Early return if there are truly no workspaces for this organization
    if (newOrganizationWorkspaces.length === 0) return;

    const newWorkspace =
      newOrganizationWorkspaces.find((workspace) => workspace.default) ||
      newOrganizationWorkspaces[0];

    if (newWorkspace) {
      navigate({
        to: "/$workspaceName",
        params: { workspaceName: newWorkspace.workspaceName },
      });
      setIsOrgSwitcherOpen(false);
    }
  };

  // Filter out default workspace from selectable options
  const selectableWorkspaces = useMemo(() => {
    if (!allWorkspaces) return [];
    return allWorkspaces.filter(
      (workspace) => workspace.workspaceName !== DEFAULT_WORKSPACE_NAME,
    );
  }, [allWorkspaces]);

  // Get workspaces for current organization
  const currentOrgWorkspaces = useMemo(() => {
    if (!selectableWorkspaces || !currentOrganization) return [];
    return selectableWorkspaces.filter(
      (workspace) => workspace.organizationId === currentOrganization.id,
    );
  }, [selectableWorkspaces, currentOrganization]);

  // Filter workspaces by search query
  const filteredWorkspaces = useMemo(() => {
    if (!search) return currentOrgWorkspaces;

    const searchLower = toLower(search);
    return currentOrgWorkspaces.filter((workspace) => {
      const displayName = calculateWorkspaceName(workspace.workspaceName);
      return toLower(displayName).includes(searchLower);
    });
  }, [currentOrgWorkspaces, search]);

  // Sort workspaces
  const sortedWorkspaces = useMemo(() => {
    return sortBy(filteredWorkspaces, "workspaceName");
  }, [filteredWorkspaces]);

  // Sort organizations for switcher
  const sortedOrganizations = useMemo(() => {
    if (!organizations) return [];
    return sortBy(organizations, "name");
  }, [organizations]);

  // Check if user has multiple organizations
  const hasMultipleOrganizations = useMemo(() => {
    return organizations && organizations.length > 1;
  }, [organizations]);

  // Get current workspace display name early so we can show it even during loading
  const currentWorkspaceDisplayName = calculateWorkspaceName(workspaceName);

  // If still loading, show loading state
  if (isLoading) {
    return (
      <div className="flex h-8 items-center">
        <div className="h-4 w-24 animate-pulse rounded bg-muted" />
      </div>
    );
  }

  // If user is not logged in or data is not available, just show workspace name
  if (!user?.loggedIn || !allWorkspaces || !organizations) {
    return (
      <div className="flex h-8 items-center">
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

  // If no current organization (e.g., opensource), just show workspace name without dropdown
  if (!currentOrganization) {
    return (
      <div className="flex h-8 items-center">
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

  // Determine if we should show dropdown - check if there are workspaces in current org
  const shouldShowDropdown = currentOrgWorkspaces.length > 0;
  const handleOpenChange = (open: boolean) => {
    setIsDropdownOpen(open);
    if (!open) {
      setSearch("");
      setIsOrgSwitcherOpen(false);
    }
  };

  const handleManageWorkspaces = () => {
    setIsDropdownOpen(false);
  };

  if (!shouldShowDropdown) {
    // Single workspace or only Personal workspace - just show it as a link (no dropdown)
    return (
      <div className="flex h-8 items-center">
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
      {isDropdownOpen ? (
        <ChevronUp className="size-4 shrink-0 text-muted-slate" />
      ) : (
        <ChevronDown className="size-4 shrink-0 text-muted-slate" />
      )}
    </button>
  );

  return (
    <div className="flex h-8 items-center gap-0.5">
      {nameLink}
      <DropdownMenu open={isDropdownOpen} onOpenChange={handleOpenChange}>
        <DropdownMenuTrigger asChild>{chevronButton}</DropdownMenuTrigger>
        <DropdownMenuContent className="w-80 p-1" align="start">
          {/* Organization Switcher */}
          <div className="flex h-10 items-center p-2">
            <TooltipWrapper content={currentOrganization.name}>
              <span className="comet-body-s min-w-0 flex-1 truncate pr-3">
                {currentOrganization.name}
              </span>
            </TooltipWrapper>
            <div className="flex shrink-0 items-center">
              {/* Settings button - opens admin dashboard (only for org admins) */}
              {currentOrganization.role === ORGANIZATION_ROLE_TYPE.admin && (
                <>
                  <TooltipWrapper content="Organization Settings">
                    <button
                      type="button"
                      className="flex size-8 cursor-pointer items-center justify-center rounded-md border-0 bg-transparent hover:bg-primary-foreground focus:outline-none"
                      onClick={(e) => {
                        e.stopPropagation();
                        if (currentOrganization && workspaceName) {
                          window.location.href = buildUrl(
                            `organizations/${currentOrganization.id}`,
                            workspaceName,
                          );
                        }
                      }}
                    >
                      <Settings2 className="size-3.5 text-muted-slate" />
                    </button>
                  </TooltipWrapper>
                  {/* Vertical separator - only show if switch org button is also visible */}
                  {hasMultipleOrganizations && (
                    <div className="h-4 w-px bg-border" />
                  )}
                </>
              )}
              {/* Switch org button - opens org dropdown (only if multiple orgs) */}
              {hasMultipleOrganizations && (
                <DropdownMenu
                  open={isOrgSwitcherOpen}
                  onOpenChange={setIsOrgSwitcherOpen}
                >
                  <DropdownMenuTrigger asChild>
                    <button
                      type="button"
                      className="flex h-8 shrink-0 cursor-pointer items-center rounded-md border-0 bg-transparent px-2 hover:bg-primary-foreground focus:outline-none"
                      onClick={(e) => {
                        e.stopPropagation();
                      }}
                    >
                      <span className="text-xs text-muted-slate">
                        Switch org
                      </span>
                    </button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent
                    className="w-60 p-1"
                    align="start"
                    side="right"
                    sideOffset={18}
                    alignOffset={-8}
                    onCloseAutoFocus={(e) => e.preventDefault()}
                  >
                    <div
                      className="comet-body-s-accented flex items-center gap-2 p-2 text-foreground"
                      onKeyDown={(e) => e.stopPropagation()}
                    >
                      <ChevronLeft className="size-4 shrink-0 text-muted-slate" />
                      <span>Switch organization</span>
                    </div>
                    <div className="max-h-[200px] overflow-auto">
                      {sortedOrganizations.length > 0 ? (
                        sortedOrganizations.map((org) => (
                          <DropdownMenuCheckboxItem
                            checked={currentOrganization.id === org.id}
                            key={org.id}
                            onClick={() => handleChangeOrganization(org)}
                          >
                            <TooltipWrapper content={org.name}>
                              <span className="min-w-0 flex-1 truncate text-left">
                                {org.name}
                              </span>
                            </TooltipWrapper>
                          </DropdownMenuCheckboxItem>
                        ))
                      ) : (
                        <div className="flex min-h-[120px] flex-col items-center justify-center px-4 py-2 text-center">
                          <div className="comet-body-s text-muted-slate">
                            No organizations found
                          </div>
                        </div>
                      )}
                    </div>
                  </DropdownMenuContent>
                </DropdownMenu>
              )}
            </div>
          </div>

          <Separator />

          {/* Search */}
          <div className="p-1">
            <SearchInput
              searchText={search}
              setSearchText={setSearch}
              placeholder="Find workspace"
              variant="default"
              size="sm"
            />
          </div>

          {/* Workspaces List */}
          <div className="max-h-[300px] overflow-auto pl-1">
            {sortedWorkspaces.length > 0 ? (
              sortedWorkspaces.map((workspace) => {
                const displayName = calculateWorkspaceName(
                  workspace.workspaceName,
                );
                const isSelected = workspace.workspaceName === workspaceName;

                return (
                  <DropdownMenuCheckboxItem
                    checked={isSelected}
                    key={workspace.workspaceName}
                    onClick={() => handleChangeWorkspace(workspace)}
                    className="mx-px"
                  >
                    <TooltipWrapper content={displayName}>
                      <span className="min-w-0 flex-1 truncate text-left">
                        {displayName}
                      </span>
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

          {/* Manage Workspaces */}
          <div className="sticky inset-x-0 bottom-0">
            <Separator className="my-1" />
            <a
              className="relative flex h-10 cursor-pointer items-center justify-center gap-0.5 rounded-md px-4 hover:bg-primary-foreground"
              href={buildUrl("account-settings/workspaces", workspaceName)}
              onClick={handleManageWorkspaces}
            >
              <Settings className="size-3.5 shrink-0 text-primary" />
              <span className="comet-body-s text-primary">
                Manage workspaces
              </span>
            </a>
          </div>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
};

export default WorkspaceSelector;
