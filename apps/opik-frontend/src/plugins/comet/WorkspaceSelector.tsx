import React, { useCallback, useMemo, useState } from "react";
import { Link, useNavigate } from "@tanstack/react-router";
import { ChevronDown, ChevronUp, Settings2 } from "lucide-react";
import sortBy from "lodash/sortBy";
import toLower from "lodash/toLower";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuPortal,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { ListAction } from "@/components/ui/list-action";
import { Separator } from "@/components/ui/separator";
import { useToast } from "@/components/ui/use-toast";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { calculateWorkspaceName, cn } from "@/lib/utils";
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

type WorkspaceLinkProps = {
  workspaceName: string;
  children?: React.ReactNode;
  className?: string;
};

const WorkspaceLink: React.FC<WorkspaceLinkProps> = ({
  workspaceName,
  className,
  children,
}) => {
  const displayName = calculateWorkspaceName(workspaceName);

  return (
    <div className={cn("flex h-8 items-center", className)}>
      <Link
        to="/$workspaceName/home"
        params={{ workspaceName }}
        className="comet-body-s min-w-0 flex-1 truncate text-left hover:underline"
      >
        {displayName}
      </Link>
      {children}
    </div>
  );
};

const WorkspaceSelector: React.FC = () => {
  const navigate = useNavigate();
  const { toast } = useToast();
  const [search, setSearch] = useState("");
  const [isDropdownOpen, setIsDropdownOpen] = useState(false);
  const [isOrgSubmenuOpen, setIsOrgSubmenuOpen] = useState(false);

  const { data: user } = useUser();
  const { data: userInvitedWorkspaces, isLoading } = useUserInvitedWorkspaces({
    enabled: !!user?.loggedIn,
  });
  const { data: organizations } = useOrganizations({
    enabled: !!user?.loggedIn,
  });
  const currentOrganization = useCurrentOrganization();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const handleOpenChange = useCallback(
    (open: boolean) => {
      // If closing and submenu is open, close only the submenu first
      if (!open && isOrgSubmenuOpen) {
        setIsOrgSubmenuOpen(false);
        return;
      }

      setIsDropdownOpen(open);
      if (!open) {
        setSearch("");
      }
    },
    [isOrgSubmenuOpen],
  );

  const handleChangeWorkspace = useCallback(
    (newWorkspace: Workspace) => {
      navigate({
        to: "/$workspaceName",
        params: { workspaceName: newWorkspace.workspaceName },
      });
    },
    [navigate],
  );

  const handleChangeOrganization = useCallback(
    (newOrganization: Organization) => {
      const newOrganizationWorkspaces =
        userInvitedWorkspaces?.filter(
          (workspace) => workspace.organizationId === newOrganization.id,
        ) || [];

      if (newOrganizationWorkspaces.length === 0) {
        toast({
          description: `You are not part of any workspaces in ${newOrganization.name}, please ask to be invited to one`,
          variant: "destructive",
        });
        return;
      }

      const newWorkspace =
        newOrganizationWorkspaces.find((workspace) => workspace.default) ||
        newOrganizationWorkspaces[0];

      if (newWorkspace) {
        navigate({
          to: "/$workspaceName",
          params: { workspaceName: newWorkspace.workspaceName },
        });
      }
    },
    [navigate, userInvitedWorkspaces, toast],
  );

  const handleOrgSettingsClick = useCallback(() => {
    if (currentOrganization && workspaceName) {
      window.location.href = buildUrl(
        `organizations/${currentOrganization.id}`,
        workspaceName,
      );
    }
  }, [currentOrganization, workspaceName]);

  // Filter member workspaces (workspaces user is invited to) by current organization
  // Excludes default workspace from selectable options
  const memberWorkspaces = useMemo(() => {
    if (!userInvitedWorkspaces || !currentOrganization) return [];
    return userInvitedWorkspaces.filter(
      (workspace) =>
        workspace.organizationId === currentOrganization.id &&
        workspace.workspaceName !== DEFAULT_WORKSPACE_NAME,
    );
  }, [userInvitedWorkspaces, currentOrganization]);

  // Filter workspaces by search query
  const filteredWorkspaces = useMemo(() => {
    if (!search) return memberWorkspaces;

    const searchLower = toLower(search);
    return memberWorkspaces.filter((workspace) => {
      const displayName = calculateWorkspaceName(workspace.workspaceName);
      return toLower(displayName).includes(searchLower);
    });
  }, [memberWorkspaces, search]);

  // Sort workspaces and organizations
  const sortedWorkspaces = useMemo(
    () => sortBy(filteredWorkspaces, "workspaceName"),
    [filteredWorkspaces],
  );

  const sortedOrganizations = useMemo(() => {
    if (!organizations) return [];
    return sortBy(organizations, "name");
  }, [organizations]);

  // Simple computed values - no memoization needed
  const hasMultipleOrganizations = organizations && organizations.length > 1;
  const hasMemberWorkspaces = memberWorkspaces.length > 0;
  // Show dropdown if there are workspaces to select OR if user can switch orgs
  const shouldShowDropdown = hasMemberWorkspaces || hasMultipleOrganizations;
  const isOrgAdmin = currentOrganization?.role === ORGANIZATION_ROLE_TYPE.admin;

  // Loading state
  if (isLoading) {
    return (
      <div className="flex h-8 items-center">
        <div className="h-4 w-24 animate-pulse rounded bg-muted" />
      </div>
    );
  }

  // Not logged in or missing data - show simple link
  if (!user?.loggedIn || !organizations) {
    return <WorkspaceLink workspaceName={workspaceName} />;
  }

  // No current organization (e.g., opensource) - show simple link
  if (!currentOrganization) {
    return <WorkspaceLink workspaceName={workspaceName} />;
  }

  // Single workspace or only Personal workspace - show simple link
  if (!shouldShowDropdown) {
    return <WorkspaceLink workspaceName={workspaceName} />;
  }

  // Multiple workspaces - show name as link and chevron as dropdown trigger
  return (
    <WorkspaceLink className="gap-0.5" workspaceName={workspaceName}>
      <DropdownMenu open={isDropdownOpen} onOpenChange={handleOpenChange}>
        <DropdownMenuTrigger asChild>
          <Button
            variant="ghost"
            size="icon-2xs"
            className="shrink-0 border border-transparent text-muted-slate hover:bg-primary-foreground hover:text-muted-slate focus-visible:ring-0 focus-visible:ring-offset-0"
          >
            {isDropdownOpen ? <ChevronUp /> : <ChevronDown />}
          </Button>
        </DropdownMenuTrigger>

        <DropdownMenuContent className="w-[320px] p-1" align="start">
          {/* Organization Header */}
          <div className="flex items-center justify-between gap-2 px-3 py-2">
            <TooltipWrapper content={currentOrganization.name}>
              <span className="comet-body-s-accented min-w-0 flex-1 truncate text-foreground">
                {currentOrganization.name}
              </span>
            </TooltipWrapper>

            <div className="flex shrink-0 items-center gap-1">
              {/* Settings button - only for org admins */}
              {isOrgAdmin && (
                <TooltipWrapper content="Organization settings">
                  <Button
                    variant="minimal"
                    size="icon-xs"
                    onClick={handleOrgSettingsClick}
                  >
                    <Settings2 className="size-3.5" />
                  </Button>
                </TooltipWrapper>
              )}

              {/* Separator between icon buttons */}
              {isOrgAdmin && hasMultipleOrganizations && (
                <Separator orientation="vertical" className="h-3.5" />
              )}

              {/* Switch org submenu - only if multiple orgs */}
              {hasMultipleOrganizations && (
                <DropdownMenuSub
                  open={isOrgSubmenuOpen}
                  onOpenChange={setIsOrgSubmenuOpen}
                >
                  <DropdownMenuSubTrigger className="size-6 justify-center p-0 text-light-slate [&>svg]:ml-0" />
                  <DropdownMenuPortal>
                    <DropdownMenuSubContent className="w-[244px] p-1">
                      <div className="flex items-center gap-1 px-2 py-2 pl-8">
                        <span className="comet-body-s-accented text-foreground">
                          Switch organization
                        </span>
                      </div>
                      <div className="max-h-[60vh] overflow-auto">
                        {sortedOrganizations.length > 0 ? (
                          sortedOrganizations.map((org) => (
                            <DropdownMenuCheckboxItem
                              key={org.id}
                              checked={currentOrganization.id === org.id}
                              onCheckedChange={() => {
                                handleChangeOrganization(org);
                                setIsOrgSubmenuOpen(false);
                              }}
                              onSelect={(e) => e.preventDefault()}
                              className="h-10 cursor-pointer data-[state=checked]:bg-primary-foreground"
                            >
                              <TooltipWrapper content={org.name}>
                                <span className="comet-body-s min-w-0 flex-1 truncate text-left">
                                  {org.name}
                                </span>
                              </TooltipWrapper>
                            </DropdownMenuCheckboxItem>
                          ))
                        ) : (
                          <div className="flex min-h-[120px] flex-col items-center justify-center px-4 py-2 text-center">
                            <span className="comet-body-s text-muted-slate">
                              No organizations found
                            </span>
                          </div>
                        )}
                      </div>
                    </DropdownMenuSubContent>
                  </DropdownMenuPortal>
                </DropdownMenuSub>
              )}
            </div>
          </div>

          <DropdownMenuSeparator className="my-1" />

          {/* Search */}
          <div className="px-1" onKeyDown={(e) => e.stopPropagation()}>
            <SearchInput
              searchText={search}
              setSearchText={setSearch}
              placeholder="Search"
              variant="ghost"
            />
          </div>

          <DropdownMenuSeparator className="my-1" />

          {/* Workspaces List */}
          <div className="max-h-[50vh] overflow-auto">
            {sortedWorkspaces.length > 0 ? (
              sortedWorkspaces.map((workspace) => {
                const displayName = calculateWorkspaceName(
                  workspace.workspaceName,
                );
                const isSelected = workspace.workspaceName === workspaceName;

                return (
                  <DropdownMenuCheckboxItem
                    key={workspace.workspaceName}
                    checked={isSelected}
                    onCheckedChange={() => {
                      handleChangeWorkspace(workspace);
                      setIsDropdownOpen(false);
                      setSearch("");
                    }}
                    onSelect={(e) => e.preventDefault()}
                    className="h-10 cursor-pointer data-[state=checked]:bg-primary-foreground"
                  >
                    <TooltipWrapper content={displayName}>
                      <span className="comet-body-s min-w-0 flex-1 truncate text-left">
                        {displayName}
                      </span>
                    </TooltipWrapper>
                  </DropdownMenuCheckboxItem>
                );
              })
            ) : (
              <div className="flex min-h-[120px] flex-col items-center justify-center px-4 py-2 text-center">
                <span className="comet-body-s text-muted-slate">
                  No workspaces found
                </span>
              </div>
            )}
          </div>

          {/* Manage Workspaces */}
          <DropdownMenuSeparator className="my-1" />
          <ListAction asChild>
            <a href={buildUrl("account-settings/workspaces", workspaceName)}>
              Manage workspaces
            </a>
          </ListAction>
        </DropdownMenuContent>
      </DropdownMenu>
    </WorkspaceLink>
  );
};

export default WorkspaceSelector;
