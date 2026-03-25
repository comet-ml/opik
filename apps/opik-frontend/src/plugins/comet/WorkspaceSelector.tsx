import React from "react";
import { Link } from "@tanstack/react-router";
import { ChevronDown, ChevronUp, Settings2 } from "lucide-react";

import { Button } from "@/ui/button";
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
} from "@/ui/dropdown-menu";
import { ListAction } from "@/ui/list-action";
import { Separator } from "@/ui/separator";
import SearchInput from "@/shared/SearchInput/SearchInput";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { calculateWorkspaceName, cn } from "@/lib/utils";
import useWorkspaceSelectorData from "@/plugins/comet/useWorkspaceSelectorData";
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
    handleOrgSettingsClick,

    sortedWorkspaces,
    sortedOrganizations,

    shouldShowDropdown,
    hasMultipleOrganizations,
    isOrgAdmin,
  } = useWorkspaceSelectorData();

  if (isLoading) {
    return (
      <div className="flex h-8 items-center">
        <div className="h-4 w-24 animate-pulse rounded bg-muted" />
      </div>
    );
  }

  if (!user?.loggedIn || !currentOrganization || !shouldShowDropdown) {
    return <WorkspaceLink workspaceName={workspaceName} />;
  }

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
          <div className="flex items-center justify-between gap-2 px-3 py-2">
            <TooltipWrapper content={currentOrganization.name}>
              <span className="comet-body-s-accented min-w-0 flex-1 truncate text-foreground">
                {currentOrganization.name}
              </span>
            </TooltipWrapper>

            <div className="flex shrink-0 items-center gap-1">
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

              {isOrgAdmin && hasMultipleOrganizations && (
                <Separator orientation="vertical" className="h-3.5" />
              )}

              {hasMultipleOrganizations && (
                <DropdownMenuSub
                  open={isOrgSubmenuOpen}
                  onOpenChange={setIsOrgSubmenuOpen}
                >
                  <DropdownMenuSubTrigger className="size-6 justify-center p-0 text-light-slate [&>svg]:ml-0" />
                  <DropdownMenuPortal>
                    <DropdownMenuSubContent className="w-[244px] p-1">
                      <div className="flex items-center gap-1 p-2 pl-8">
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

          <div className="px-1" onKeyDown={(e) => e.stopPropagation()}>
            <SearchInput
              searchText={search}
              setSearchText={setSearch}
              placeholder="Search"
              variant="ghost"
            />
          </div>

          <DropdownMenuSeparator className="my-1" />

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
