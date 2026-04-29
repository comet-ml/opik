import React from "react";
import { Settings2 } from "lucide-react";

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
import { buildUrl } from "@/plugins/comet/utils";

const canManageOrg = (org: Organization) =>
  org.role === ORGANIZATION_ROLE_TYPE.admin;

const orgSettingsUrl = (orgId: string, workspaceName: string) =>
  buildUrl(`organizations/${orgId}`, workspaceName);

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
}) => (
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
        placeholder="Search"
        variant="ghost"
        size="sm"
      />
    </div>

    <DropdownMenuSeparator className="my-1" />

    <div className="max-h-[50vh] overflow-auto">
      {sortedWorkspaces.length > 0 ? (
        sortedWorkspaces.map((workspace) => {
          const wsDisplayName = calculateWorkspaceName(workspace.workspaceName);
          const isSelected = workspace.workspaceName === workspaceName;

          return (
            <DropdownMenuItem
              key={workspace.workspaceName}
              size="sm"
              selected={isSelected}
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
            </DropdownMenuItem>
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

    <ListAction variant="default" size="sm" asChild>
      <a href={buildUrl("account-settings/workspaces", workspaceName)}>
        <Settings2 className="size-3.5 text-light-slate" />
        <span>Manage workspaces</span>
      </a>
    </ListAction>
  </>
);

export default WorkspaceMenuContent;
