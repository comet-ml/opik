import React, { useMemo, useRef } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { ArrowUpRight, Pin, PinOff, Settings2 } from "lucide-react";

import { Button } from "@/ui/button";
import {
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuPortal,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
} from "@/ui/dropdown-menu";
import { ListAction } from "@/ui/list-action";
import SearchInput from "@/shared/SearchInput/SearchInput";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { calculateWorkspaceName, cn } from "@/lib/utils";
import {
  Organization,
  ORGANIZATION_ROLE_TYPE,
  Workspace,
} from "@/plugins/comet/types";
import usePinnedWorkspaces from "@/plugins/comet/usePinnedWorkspaces";
import { buildUrl } from "@/plugins/comet/utils";

const WORKSPACE_ITEM_HEIGHT = 32;
const WORKSPACE_VIRTUALIZATION_THRESHOLD = 50;
const WORKSPACE_VIRTUAL_OVERSCAN = 8;
const RECENTLY_VISITED_SIZE = 10;

const canManageOrg = (org: Organization) =>
  org.role === ORGANIZATION_ROLE_TYPE.admin;

const orgSettingsUrl = (orgId: string, workspaceName: string) =>
  buildUrl(`organizations/${orgId}`, workspaceName);

const MenuSectionLabel: React.FC<
  React.PropsWithChildren<{ className?: string }>
> = ({ className, children }) => (
  <div
    className={cn(
      "comet-body-s-accented flex h-8 items-center px-3 text-light-slate",
      className,
    )}
  >
    {children}
  </div>
);

interface WorkspaceMenuContentProps {
  workspaceName: string;
  currentOrganization: Organization;
  sortedWorkspaces: Workspace[];
  sortedOrganizations: Organization[];
  hasMultipleOrganizations: boolean | undefined;
  isOrgSubmenuOpen: boolean;
  setIsOrgSubmenuOpen: (open: boolean) => void;
  setIsDropdownOpen: (open: boolean) => void;
  handleChangeWorkspace: (workspace: Workspace) => void;
  handleChangeOrganization: (org: Organization) => void;
  search: string;
  setSearch: (value: string) => void;
}

const WorkspaceMenuContent: React.FC<WorkspaceMenuContentProps> = ({
  workspaceName,
  currentOrganization,
  sortedWorkspaces,
  sortedOrganizations,
  hasMultipleOrganizations,
  isOrgSubmenuOpen,
  setIsOrgSubmenuOpen,
  setIsDropdownOpen,
  handleChangeWorkspace,
  handleChangeOrganization,
  search,
  setSearch,
}) => {
  const scrollRef = useRef<HTMLDivElement>(null);

  const {
    pinnedWorkspaces: pinnedConfig,
    isPinned,
    pinWorkspace,
    unpinWorkspace,
  } = usePinnedWorkspaces(currentOrganization.id);

  const isSearching = search.trim().length > 0;

  // When searching, show all matches flat (virtualized above the threshold).
  // Otherwise cluster into Pinned + Recently visited (capped) sections.
  const workspacesById = useMemo(
    () =>
      new Map(
        sortedWorkspaces.map((workspace) => [workspace.workspaceId, workspace]),
      ),
    [sortedWorkspaces],
  );
  // Pinned rows keep the hook's insertion order (stable, like the projects menu),
  // limited to workspaces present in the current (search-filtered) list.
  const pinnedWorkspaces = useMemo(
    () =>
      pinnedConfig
        .map((pinned) => workspacesById.get(pinned.workspaceId))
        .filter((workspace): workspace is Workspace => Boolean(workspace)),
    [pinnedConfig, workspacesById],
  );
  const recentlyVisitedWorkspaces = useMemo(
    () =>
      sortedWorkspaces
        .filter((workspace) => !isPinned(workspace.workspaceId))
        .slice(0, RECENTLY_VISITED_SIZE),
    [sortedWorkspaces, isPinned],
  );

  const shouldVirtualize =
    isSearching && sortedWorkspaces.length > WORKSPACE_VIRTUALIZATION_THRESHOLD;

  const virtualizer = useVirtualizer({
    count: shouldVirtualize ? sortedWorkspaces.length : 0,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => WORKSPACE_ITEM_HEIGHT,
    overscan: WORKSPACE_VIRTUAL_OVERSCAN,
  });

  const handleTogglePin = (workspace: Workspace, pinned: boolean) => {
    if (pinned) {
      pinWorkspace(workspace);
    } else {
      unpinWorkspace(workspace.workspaceId);
    }
  };

  const renderWorkspaceItem = (workspace: Workspace) => {
    const wsDisplayName = calculateWorkspaceName(workspace.workspaceName);
    const isSelected = workspace.workspaceName === workspaceName;
    const pinned = isPinned(workspace.workspaceId);

    return (
      <DropdownMenuItem
        key={workspace.workspaceName}
        size="sm"
        selected={isSelected}
        className="group rounded-md pr-1.5"
        onSelect={(e) => {
          e.preventDefault();
          handleChangeWorkspace(workspace);
          setIsDropdownOpen(false);
          setSearch("");
        }}
      >
        <TooltipWrapper content={wsDisplayName}>
          <span className="comet-body-s min-w-0 flex-1 truncate text-left">
            {wsDisplayName}
          </span>
        </TooltipWrapper>
        <TooltipWrapper content={pinned ? "Unpin workspace" : "Pin workspace"}>
          <Button
            variant="minimal"
            size="icon-2xs"
            aria-label={pinned ? "Unpin workspace" : "Pin workspace"}
            className={cn(
              "group/pin shrink-0 rounded text-light-slate hover:text-foreground",
              pinned ? "inline-flex" : "hidden group-hover:inline-flex",
            )}
            onClick={(e) => {
              e.stopPropagation();
              handleTogglePin(workspace, !pinned);
            }}
          >
            {pinned ? (
              <>
                <Pin className="group-hover/pin:hidden" />
                <PinOff className="hidden group-hover/pin:block" />
              </>
            ) : (
              <Pin />
            )}
          </Button>
        </TooltipWrapper>
      </DropdownMenuItem>
    );
  };

  return (
    <>
      {hasMultipleOrganizations ? (
        <DropdownMenuSub
          open={isOrgSubmenuOpen}
          onOpenChange={setIsOrgSubmenuOpen}
        >
          <DropdownMenuSubTrigger
            variant="menu"
            size="sm"
            className="cursor-pointer"
          >
            <TooltipWrapper content={currentOrganization.name}>
              <span className="min-w-0 flex-1 truncate text-left">
                {currentOrganization.name}
              </span>
            </TooltipWrapper>
          </DropdownMenuSubTrigger>
          <DropdownMenuPortal>
            <DropdownMenuSubContent className="w-[280px] p-1" sideOffset={8}>
              <DropdownMenuLabel size="sm">Organizations</DropdownMenuLabel>
              <DropdownMenuSeparator className="my-1" />
              <div className="max-h-[60vh] overflow-auto">
                {sortedOrganizations.length > 0 ? (
                  sortedOrganizations.map((org) => {
                    const isActive = currentOrganization.id === org.id;
                    return (
                      <DropdownMenuItem
                        key={org.id}
                        size="sm"
                        selected={isActive}
                        onSelect={(e) => {
                          e.preventDefault();
                          handleChangeOrganization(org);
                          setIsOrgSubmenuOpen(false);
                        }}
                        className="group pr-1.5"
                      >
                        <TooltipWrapper content={org.name}>
                          <span className="comet-body-s min-w-0 flex-1 truncate text-left">
                            {org.name}
                          </span>
                        </TooltipWrapper>
                        {canManageOrg(org) && (
                          <Button
                            variant="minimal"
                            size="icon-2xs"
                            onClick={(e) => {
                              e.stopPropagation();
                              window.location.href = orgSettingsUrl(
                                org.id,
                                workspaceName,
                              );
                            }}
                            aria-label={`Open ${org.name} settings`}
                            className={cn(
                              "shrink-0 text-light-slate hover:text-foreground",
                              !isActive && "invisible group-hover:visible",
                            )}
                          >
                            <Settings2 className="size-3.5" />
                          </Button>
                        )}
                      </DropdownMenuItem>
                    );
                  })
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
      ) : (
        <div className="flex h-8 items-center gap-1.5 px-3 pr-1.5">
          <TooltipWrapper content={currentOrganization.name}>
            <span className="comet-body-s-accented min-w-0 flex-1 truncate text-foreground">
              {currentOrganization.name}
            </span>
          </TooltipWrapper>
          {canManageOrg(currentOrganization) && (
            <Button
              variant="minimal"
              size="icon-2xs"
              onClick={() => {
                window.location.href = orgSettingsUrl(
                  currentOrganization.id,
                  workspaceName,
                );
              }}
              aria-label={`Open ${currentOrganization.name} settings`}
              className="shrink-0 text-light-slate hover:text-foreground"
            >
              <Settings2 className="size-3.5" />
            </Button>
          )}
        </div>
      )}

      <DropdownMenuSeparator className="my-1" />

      <div className="px-0.5" onKeyDown={(e) => e.stopPropagation()}>
        <SearchInput
          searchText={search}
          setSearchText={setSearch}
          placeholder="Search workspace"
          variant="ghost"
          dimension="sm"
        />
      </div>

      <DropdownMenuSeparator className="my-1" />

      <div
        ref={scrollRef}
        className="max-h-[50vh] overflow-y-auto overflow-x-hidden"
      >
        {sortedWorkspaces.length === 0 ? (
          <div className="flex min-h-[120px] flex-col items-center justify-center px-4 py-2 text-center">
            <span className="comet-body-s text-muted-slate">
              No workspaces found
            </span>
          </div>
        ) : isSearching ? (
          shouldVirtualize ? (
            <div
              style={{
                height: `${virtualizer.getTotalSize()}px`,
                position: "relative",
              }}
            >
              {virtualizer.getVirtualItems().map((virtualRow) => (
                <div
                  key={virtualRow.key}
                  style={{
                    position: "absolute",
                    top: 0,
                    left: 0,
                    width: "100%",
                    height: `${virtualRow.size}px`,
                    transform: `translateY(${virtualRow.start}px)`,
                  }}
                >
                  {renderWorkspaceItem(sortedWorkspaces[virtualRow.index])}
                </div>
              ))}
            </div>
          ) : (
            sortedWorkspaces.map(renderWorkspaceItem)
          )
        ) : (
          <>
            {pinnedWorkspaces.length > 0 && (
              <>
                <MenuSectionLabel>Pinned</MenuSectionLabel>
                {pinnedWorkspaces.map(renderWorkspaceItem)}
              </>
            )}
            {recentlyVisitedWorkspaces.length > 0 && (
              <>
                {pinnedWorkspaces.length > 0 && (
                  <DropdownMenuSeparator className="my-1" />
                )}
                <MenuSectionLabel>Recently visited</MenuSectionLabel>
                {recentlyVisitedWorkspaces.map(renderWorkspaceItem)}
              </>
            )}
          </>
        )}
      </div>

      <DropdownMenuSeparator className="my-1" />

      <ListAction variant="default" size="sm" asChild>
        <a href={buildUrl("account-settings/workspaces", workspaceName)}>
          <span>View all workspaces</span>
          <ArrowUpRight className="size-3.5 shrink-0 text-light-slate" />
        </a>
      </ListAction>
    </>
  );
};

export default WorkspaceMenuContent;
