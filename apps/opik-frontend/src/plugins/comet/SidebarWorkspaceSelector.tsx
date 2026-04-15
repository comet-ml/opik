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
import { Separator } from "@/ui/separator";
import SearchInput from "@/shared/SearchInput/SearchInput";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { calculateWorkspaceName, cn } from "@/lib/utils";
import useWorkspaceSelectorData from "@/plugins/comet/useWorkspaceSelectorData";
import { buildUrl } from "@/plugins/comet/utils";

interface SidebarWorkspaceSelectorProps {
  expanded?: boolean;
}

const SidebarWorkspaceSelector: React.FC<SidebarWorkspaceSelectorProps> = ({
  expanded = true,
}) => {
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

  const displayName = calculateWorkspaceName(workspaceName);

  if (isLoading) {
    return expanded ? (
      <div className="flex h-8 items-center px-2">
        <div className="h-4 w-24 animate-pulse rounded bg-muted" />
      </div>
    ) : (
      <div className="flex size-7 items-center justify-center">
        <div className="size-4 animate-pulse rounded bg-muted" />
      </div>
    );
  }

  if (!user?.loggedIn || !currentOrganization || !shouldShowDropdown) {
    return expanded ? (
      <Link
        to="/$workspaceName/home"
        params={{ workspaceName }}
        className="comet-body-s-accented truncate rounded-md px-2 py-1 text-foreground hover:bg-primary-foreground"
      >
        {displayName}
      </Link>
    ) : null;
  }

  return (
    <DropdownMenu open={isDropdownOpen} onOpenChange={handleOpenChange}>
      <DropdownMenuTrigger asChild>
        {expanded ? (
          <button
            className={cn(
              "flex w-full items-center gap-1 rounded-md px-2 py-1",
              isDropdownOpen
                ? "bg-primary-foreground"
                : "hover:bg-primary-foreground",
            )}
          >
            <span className="comet-body-s-accented flex-1 truncate text-left text-foreground">
              {displayName}
            </span>
            <span className="shrink-0 text-muted-slate">
              {isDropdownOpen ? (
                <ChevronUp className="size-3.5" />
              ) : (
                <ChevronDown className="size-3.5" />
              )}
            </span>
          </button>
        ) : (
          <button
            className={cn(
              "flex size-7 items-center justify-center rounded-md",
              isDropdownOpen
                ? "bg-primary-foreground"
                : "hover:bg-primary-foreground",
            )}
          >
            {isDropdownOpen ? (
              <ChevronUp className="size-3.5 text-foreground" />
            ) : (
              <ChevronDown className="size-3.5 text-foreground" />
            )}
          </button>
        )}
      </DropdownMenuTrigger>

      <DropdownMenuContent
        className="w-[240px] p-1"
        side="top"
        align="start"
        sideOffset={4}
      >
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
              const wsDisplayName = calculateWorkspaceName(
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
                  <TooltipWrapper content={wsDisplayName}>
                    <span className="comet-body-s min-w-0 flex-1 truncate text-left">
                      {wsDisplayName}
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
        <Button
          variant="ghost"
          size="sm"
          className="w-full justify-start gap-1.5 text-primary"
          asChild
        >
          <a href={buildUrl("account-settings/workspaces", workspaceName)}>
            <Settings2 className="size-3.5" />
            <span className="comet-body-s-accented">Manage workspaces</span>
          </a>
        </Button>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default SidebarWorkspaceSelector;
