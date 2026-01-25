import React from "react";
import { useNavigate } from "@tanstack/react-router";
import { ChevronDown } from "lucide-react";
import sortBy from "lodash/sortBy";

import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";
import useOrganizations from "@/plugins/comet/useOrganizations";
import useCurrentOrganization from "@/plugins/comet/useCurrentOrganization";
import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import useUserInvitedWorkspaces from "@/plugins/comet/useUserInvitedWorkspaces";
import useUser from "@/plugins/comet/useUser";
import { Organization } from "@/plugins/comet/types";

interface OrganizationSelectorProps {
  expanded: boolean;
}

const OrganizationSelector: React.FC<OrganizationSelectorProps> = ({
  expanded,
}) => {
  const navigate = useNavigate();
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

  const handleChangeOrganization = (newOrganization: Organization) => {
    if (!userInvitedWorkspaces) return;

    const newOrganizationWorkspaces = userInvitedWorkspaces.filter(
      (workspace) => workspace.organizationId === newOrganization.id,
    );

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

  if (
    !user?.loggedIn ||
    isLoading ||
    !organizations ||
    !allWorkspaces ||
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

  const triggerContent = (
    <>
      <span className="flex size-4 shrink-0 items-center justify-center rounded border border-border text-xs">
        {currentOrganization.name.charAt(0).toUpperCase()}
      </span>
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
        expanded ? "h-8 gap-1.5" : "h-8 w-8 shrink-0 justify-center gap-0",
      )}
    >
      {triggerContent}
    </button>
  );

  if (expanded) {
    return (
      <div className="w-full px-3">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>{triggerButton}</DropdownMenuTrigger>
          <DropdownMenuContent className="w-60" align="start">
            <div className="max-h-[200px] overflow-auto">
              {sortBy(organizations, "name").map((org) => (
                <DropdownMenuCheckboxItem
                  checked={currentOrganization.name === org.name}
                  key={org.name}
                  onClick={() => handleChangeOrganization(org)}
                >
                  <TooltipWrapper content={org.name}>
                    <span className="truncate">{org.name}</span>
                  </TooltipWrapper>
                </DropdownMenuCheckboxItem>
              ))}
            </div>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    );
  }

  // Collapsed state with tooltip
  return (
    <div className="w-8 shrink-0">
      <DropdownMenu>
        <TooltipWrapper content={currentOrganization.name} side="right">
          <DropdownMenuTrigger asChild>{triggerButton}</DropdownMenuTrigger>
        </TooltipWrapper>
        <DropdownMenuContent className="w-60" side="right" align="end">
          <div className="max-h-[200px] overflow-auto">
            {sortBy(organizations, "name").map((org) => (
              <DropdownMenuCheckboxItem
                checked={currentOrganization.name === org.name}
                key={org.name}
                onClick={() => handleChangeOrganization(org)}
              >
                <TooltipWrapper content={org.name}>
                  <span className="truncate">{org.name}</span>
                </TooltipWrapper>
              </DropdownMenuCheckboxItem>
            ))}
          </div>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
};

export default OrganizationSelector;
