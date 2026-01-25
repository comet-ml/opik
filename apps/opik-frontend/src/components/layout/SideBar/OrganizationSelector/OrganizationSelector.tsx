import React, { useState, useMemo } from "react";
import { useNavigate } from "@tanstack/react-router";
import { ChevronDown, Shield } from "lucide-react";
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
import { cn } from "@/lib/utils";
import { ADMIN_DASHBOARD_LABEL } from "@/constants/labels";
import useOrganizations from "@/plugins/comet/useOrganizations";
import useCurrentOrganization from "@/plugins/comet/useCurrentOrganization";
import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import useUserInvitedWorkspaces from "@/plugins/comet/useUserInvitedWorkspaces";
import useUser from "@/plugins/comet/useUser";
import useAppStore from "@/store/AppStore";
import { Organization, ORGANIZATION_ROLE_TYPE } from "@/plugins/comet/types";
import { buildUrl } from "@/plugins/comet/utils";

interface OrganizationSelectorProps {
  expanded: boolean;
}

const OrganizationSelector: React.FC<OrganizationSelectorProps> = ({
  expanded,
}) => {
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const [isDropdownOpen, setIsDropdownOpen] = useState(false);
  const { data: user } = useUser();
  const { data: organizations, isLoading } = useOrganizations({
    enabled: !!user?.loggedIn,
  });
  const { data: allWorkspaces } = useAllWorkspaces({
    enabled: !!user?.loggedIn,
  });
  const { data: userInvitedWorkspaces } = useUserInvitedWorkspaces({
    enabled: !!user?.loggedIn,
  });
  const currentOrganization = useCurrentOrganization();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

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
    }
  };

  // Filter organizations by search query (before early return)
  const filteredOrganizations = useMemo(() => {
    if (!organizations || !search) return organizations || [];
    const searchLower = toLower(search);
    return organizations.filter((org) =>
      toLower(org.name).includes(searchLower),
    );
  }, [organizations, search]);

  if (
    !user?.loggedIn ||
    isLoading ||
    !organizations ||
    !userInvitedWorkspaces ||
    !currentOrganization
  ) {
    // Show minimal placeholder while loading
    if (expanded) {
      return (
        <div className="flex h-8 w-full items-center gap-1.5 px-3">
          <div className="flex size-4 shrink-0 items-center justify-center rounded border border-border bg-muted text-xs">
            <span className="opacity-50">-</span>
          </div>
          <div className="h-4 flex-1 animate-pulse rounded bg-muted" />
        </div>
      );
    }
    return (
      <div className="flex size-8 shrink-0 items-center justify-center">
        <div className="flex size-4 items-center justify-center rounded border border-border bg-muted text-xs">
          <span className="opacity-50">-</span>
        </div>
      </div>
    );
  }

  // If only one organization, still show it but make it less interactive
  const hasMultipleOrganizations = organizations.length > 1;

  // Check if user is organization admin
  const isOrganizationAdmin =
    currentOrganization?.role === ORGANIZATION_ROLE_TYPE.admin;

  const handleOpenChange = (open: boolean) => {
    setIsDropdownOpen(open);
    if (!open) {
      setSearch("");
    }
  };

  const handleManageOrganization = () => {
    if (!currentOrganization || !workspaceName) return;
    setIsDropdownOpen(false);
    window.location.href = buildUrl(
      `organizations/${currentOrganization.id}`,
      workspaceName,
    );
  };

  const triggerContent = (
    <>
      {expanded && (
        <>
          <span className="comet-body-s min-w-0 flex-1 truncate text-left">
            {currentOrganization.name}
          </span>
          {hasMultipleOrganizations && (
            <ChevronDown className="ml-auto size-4 shrink-0 text-muted-slate" />
          )}
        </>
      )}
    </>
  );

  if (!hasMultipleOrganizations) {
    // Single organization - just show it, optionally with tooltip when collapsed
    if (expanded) {
      return (
        <div className="flex h-8 w-full items-center gap-1.5 px-3">
          {triggerContent}
        </div>
      );
    }
    return (
      <TooltipWrapper content={currentOrganization.name} side="right">
        <div className="flex size-8 shrink-0 items-center justify-center">
          {triggerContent}
        </div>
      </TooltipWrapper>
    );
  }

  // Multiple organizations - show dropdown
  const triggerButton = (
    <button
      className={cn(
        "comet-body-s flex w-full items-center rounded-md text-foreground transition-colors hover:bg-primary-foreground",
        expanded ? "h-8 gap-1.5 px-2" : "h-8 w-8 shrink-0 justify-center gap-0",
      )}
    >
      {triggerContent}
    </button>
  );

  if (expanded) {
    return (
      <div className="w-full min-w-0">
        <DropdownMenu open={isDropdownOpen} onOpenChange={handleOpenChange}>
          <DropdownMenuTrigger asChild>{triggerButton}</DropdownMenuTrigger>
          <DropdownMenuContent
            className="w-60 p-1 pt-12"
            align="start"
            onCloseAutoFocus={(e) => e.preventDefault()}
          >
            <div
              className="absolute inset-x-1 top-1 h-11"
              onKeyDown={(e) => e.stopPropagation()}
            >
              <SearchInput
                searchText={search}
                setSearchText={setSearch}
                placeholder="Find organization"
                variant="ghost"
              />
              <Separator className="mt-1" />
            </div>
            <div className="max-h-[200px] overflow-auto">
              {filteredOrganizations.length > 0 ? (
                sortBy(filteredOrganizations, "name").map((org) => (
                  <DropdownMenuCheckboxItem
                    checked={currentOrganization.name === org.name}
                    key={org.name}
                    onClick={() => handleChangeOrganization(org)}
                  >
                    <TooltipWrapper content={org.name}>
                      <span className="min-w-0 truncate">{org.name}</span>
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
            {isOrganizationAdmin && (
              <div className="sticky inset-x-0 bottom-0">
                <Separator className="my-1" />
                <div
                  className="relative flex h-10 cursor-pointer items-center rounded-md pl-8 pr-2 hover:bg-primary-foreground"
                  onClick={handleManageOrganization}
                >
                  <span className="absolute left-2 flex size-3.5 items-center justify-center">
                    <Shield className="size-3.5 shrink-0 text-primary" />
                  </span>
                  <span className="comet-body-s text-primary">
                    {ADMIN_DASHBOARD_LABEL}
                  </span>
                </div>
              </div>
            )}
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    );
  }

  // Collapsed state with tooltip
  return (
    <div className="w-8 shrink-0">
      <DropdownMenu open={isDropdownOpen} onOpenChange={handleOpenChange}>
        <TooltipWrapper content={currentOrganization.name} side="right">
          <DropdownMenuTrigger asChild>{triggerButton}</DropdownMenuTrigger>
        </TooltipWrapper>
        <DropdownMenuContent
          className="w-60 p-1 pt-12"
          side="right"
          align="end"
          onCloseAutoFocus={(e) => e.preventDefault()}
        >
          <div
            className="absolute inset-x-1 top-1 h-11"
            onKeyDown={(e) => e.stopPropagation()}
          >
            <SearchInput
              searchText={search}
              setSearchText={setSearch}
              placeholder="Find organization"
              variant="ghost"
            />
            <Separator className="mt-1" />
          </div>
          <div className="max-h-[200px] overflow-auto">
            {filteredOrganizations.length > 0 ? (
              sortBy(filteredOrganizations, "name").map((org) => (
                <DropdownMenuCheckboxItem
                  checked={currentOrganization.name === org.name}
                  key={org.name}
                  onClick={() => handleChangeOrganization(org)}
                >
                  <TooltipWrapper content={org.name}>
                    <span className="truncate">{org.name}</span>
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
          {isOrganizationAdmin && (
            <div className="sticky inset-x-0 bottom-0">
              <Separator className="my-1" />
              <div
                className="flex h-10 cursor-pointer items-center justify-start rounded-md px-4 hover:bg-primary-foreground"
                onClick={handleManageOrganization}
              >
                <div className="comet-body-s flex min-w-0 items-center gap-2 text-primary">
                  <Shield className="size-3.5 shrink-0" />
                  <span className="min-w-0 truncate">
                    {ADMIN_DASHBOARD_LABEL}
                  </span>
                </div>
              </div>
            </div>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
};

export default OrganizationSelector;
