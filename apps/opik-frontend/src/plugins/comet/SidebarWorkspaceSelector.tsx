import React from "react";
import { Link } from "@tanstack/react-router";
import { ChevronDown, ChevronUp, Settings2 } from "lucide-react";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuPortal,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
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

    sortedWorkspaces,
    sortedOrganizations,

    shouldShowDropdown,
    hasMultipleOrganizations,
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

  const initial = (displayName || workspaceName).charAt(0).toUpperCase();

  const expandedThumb = (
    <span className="comet-body-xs-accented flex size-8 shrink-0 items-center justify-center rounded-md bg-muted text-muted-slate">
      {initial}
    </span>
  );

  const collapsedThumb = (
    <span className="comet-body-xs-accented flex size-7 shrink-0 items-center justify-center rounded-md bg-muted text-[10.5px] leading-none text-muted-slate">
      {initial}
    </span>
  );

  if (!user?.loggedIn || !currentOrganization || !shouldShowDropdown) {
    return expanded ? (
      <Link
        to="/$workspaceName/home"
        params={{ workspaceName }}
        className="flex w-full items-center gap-1.5 rounded-md px-1 py-0.5 hover:bg-primary-foreground"
      >
        {expandedThumb}
        <div className="flex min-w-0 flex-1 flex-col items-stretch">
          <span className="comet-body-xs-accented text-light-slate">
            Workspace
          </span>
          <span className="comet-body-s-accented w-full truncate text-left text-foreground">
            {displayName}
          </span>
        </div>
      </Link>
    ) : (
      <Link
        to="/$workspaceName/home"
        params={{ workspaceName }}
        className="flex items-center justify-center"
      >
        {collapsedThumb}
      </Link>
    );
  }

  return (
    <DropdownMenu open={isDropdownOpen} onOpenChange={handleOpenChange}>
      <DropdownMenuTrigger asChild>
        {expanded ? (
          <button
            className={cn(
              "flex w-full items-center gap-1.5 rounded-md px-1 py-0.5",
              isDropdownOpen
                ? "bg-primary-foreground"
                : "hover:bg-primary-foreground",
            )}
          >
            {expandedThumb}
            <div className="flex min-w-0 flex-1 flex-col items-stretch">
              <span className="flex items-end gap-0.5 text-light-slate">
                <span className="comet-body-xs-accented">Workspace</span>
                {isDropdownOpen ? (
                  <ChevronUp className="size-3.5" />
                ) : (
                  <ChevronDown className="size-3.5" />
                )}
              </span>
              <span className="comet-body-s-accented w-full truncate text-left text-foreground">
                {displayName}
              </span>
            </div>
          </button>
        ) : (
          <button
            className="relative flex w-fit items-center justify-center self-center"
            aria-label="Open workspace selector"
          >
            {collapsedThumb}
            <Button
              variant="outline"
              size="icon-4xs"
              asChild
              className="pointer-events-none absolute -bottom-1 -right-1 text-foreground-secondary shadow-sm"
            >
              <span>{isDropdownOpen ? <ChevronUp /> : <ChevronDown />}</span>
            </Button>
          </button>
        )}
      </DropdownMenuTrigger>

      <DropdownMenuContent
        className="w-[280px] p-1"
        side="top"
        align="start"
        sideOffset={4}
      >
        <div className="flex h-8 items-center gap-1 px-3">
          <TooltipWrapper content={currentOrganization.name}>
            <span className="comet-body-s-accented min-w-0 flex-1 truncate text-foreground">
              {currentOrganization.name}
            </span>
          </TooltipWrapper>

          {hasMultipleOrganizations && (
            <DropdownMenuSub
              open={isOrgSubmenuOpen}
              onOpenChange={setIsOrgSubmenuOpen}
            >
              <DropdownMenuSubTrigger className="size-6 shrink-0 justify-center p-0 text-light-slate [&>svg]:ml-0" />
              <DropdownMenuPortal>
                <DropdownMenuSubContent className="w-[280px] p-1">
                  <div className="flex h-8 items-center px-3">
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
                          className="h-8 cursor-pointer data-[state=checked]:bg-primary-100 data-[state=checked]:text-primary"
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

        <div className="px-1" onKeyDown={(e) => e.stopPropagation()}>
          <SearchInput
            searchText={search}
            setSearchText={setSearch}
            placeholder="Search"
            variant="ghost"
            size="sm"
          />
        </div>

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
                  className="h-8 cursor-pointer data-[state=checked]:bg-primary-100 data-[state=checked]:text-primary"
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

        <Button
          variant="ghost"
          size="sm"
          className="comet-body-s h-8 w-full justify-start gap-2 px-3 font-normal text-foreground"
          asChild
        >
          <a href={buildUrl("account-settings/workspaces", workspaceName)}>
            <Settings2 className="size-3.5 text-light-slate" />
            <span>Manage workspaces</span>
          </a>
        </Button>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default SidebarWorkspaceSelector;
