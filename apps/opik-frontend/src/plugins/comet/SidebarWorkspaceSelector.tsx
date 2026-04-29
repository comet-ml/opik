import React from "react";
import { Link } from "@tanstack/react-router";
import { ChevronDown, ChevronUp } from "lucide-react";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { calculateWorkspaceName, cn } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import useWorkspaceSelectorData from "@/plugins/comet/useWorkspaceSelectorData";
import WorkspaceMenuContent from "@/plugins/comet/WorkspaceMenuContent";

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
      {expanded ? (
        <DropdownMenuTrigger asChild>
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
              <span className="flex items-center gap-0.5 text-light-slate">
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
        </DropdownMenuTrigger>
      ) : (
        <TooltipWrapper content="Switch workspace" side="right">
          <DropdownMenuTrigger asChild>
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
          </DropdownMenuTrigger>
        </TooltipWrapper>
      )}

      <DropdownMenuContent
        className="w-[280px] p-1"
        side="top"
        align="start"
        sideOffset={4}
      >
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

export default SidebarWorkspaceSelector;
