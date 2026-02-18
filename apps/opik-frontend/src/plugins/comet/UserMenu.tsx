import React, { useState } from "react";
import { useMatches } from "@tanstack/react-router";
import copy from "clipboard-copy";
import {
  Book,
  Check,
  Copy,
  GraduationCap,
  Grip,
  KeyRound,
  LogOut,
  Settings,
  Settings2,
  Shield,
  UserPlus,
  Zap,
} from "lucide-react";

import { useOpenQuickStartDialog } from "@/components/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuPortal,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useToast } from "@/components/ui/use-toast";
import { useThemeOptions } from "@/hooks/useThemeOptions";
import { useDateFormat } from "@/hooks/useDateFormat";
import DateFormatDropdown from "@/components/shared/DateFormatDropdown/DateFormatDropdown";
import { APP_VERSION } from "@/constants/app";
import { ADMIN_DASHBOARD_LABEL } from "@/constants/labels";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { buildDocsUrl, cn, maskAPIKey } from "@/lib/utils";
import useAppStore from "@/store/AppStore";
import api from "./api";
import { ORGANIZATION_PLAN_ENTERPRISE, ORGANIZATION_ROLE_TYPE } from "./types";
import useOrganizations from "./useOrganizations";
import useUser from "./useUser";
import useUserPermissions from "./useUserPermissions";
import { buildUrl, isOnPremise, isProduction } from "./utils";

import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import useInviteMembersURL from "@/plugins/comet/useInviteMembersURL";
import InviteUsersPopover from "@/plugins/comet/InviteUsersPopover";
import useUserPermission from "@/plugins/comet/useUserPermission";

const UserMenu = () => {
  const matches = useMatches();
  const { toast } = useToast();
  const { theme, themeOptions, CurrentIcon, handleThemeSelect } =
    useThemeOptions();
  const [dateFormat, setDateFormat] = useDateFormat();
  const { open: openQuickstart } = useOpenQuickStartDialog();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const hideUpgradeButton = matches.some(
    (match) => match.staticData?.hideUpgradeButton,
  );

  const { data: user } = useUser();
  const { data: organizations, isLoading } = useOrganizations({
    enabled: !!user?.loggedIn,
  });

  const { data: allWorkspaces } = useAllWorkspaces({
    enabled: !!user?.loggedIn,
  });

  const workspace = allWorkspaces?.find(
    (workspace) => workspace.workspaceName === workspaceName,
  );

  const { data: userPermissions } = useUserPermissions(
    {
      userName: user?.userName || "",
      organizationId: workspace?.organizationId || "",
    },
    { enabled: !!user?.loggedIn && !!workspace },
  );

  const { canInviteMembers } = useUserPermission();
  const inviteMembersURL = useInviteMembersURL();
  const [inviteSearchQuery, setInviteSearchQuery] = useState("");
  const [isInviteSubmenuOpen, setIsInviteSubmenuOpen] = useState(false);

  const isCollaboratorsTabEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.COLLABORATORS_TAB_ENABLED,
  );

  const handleInviteClose = () => {
    setIsInviteSubmenuOpen(false);
    setInviteSearchQuery("");
  };

  if (
    !user ||
    !user.loggedIn ||
    isLoading ||
    !organizations ||
    !userPermissions ||
    !allWorkspaces
  ) {
    return null;
  }

  const handleSwitchToEM = () => {
    window.location.href = buildUrl(
      workspaceName,
      workspaceName,
      "&changeApplication=em",
    );
  };

  const organization = organizations.find((org) => {
    return org.id === workspace?.organizationId;
  });

  const isOrganizationAdmin =
    organization?.role === ORGANIZATION_ROLE_TYPE.admin;

  const isAcademic = organization?.academic;

  const isEnterpriseCustomer =
    organization?.paymentPlan === ORGANIZATION_PLAN_ENTERPRISE;

  const isLLMOnlyOrganization =
    organization?.role === ORGANIZATION_ROLE_TYPE.opik;

  const renderAvatar = (clickable = false) => {
    return (
      <Avatar className={cn(clickable ? "cursor-pointer" : "")}>
        <AvatarImage src={user.profileImages.small} />
        <AvatarFallback>{user.userName.charAt(0).toUpperCase()}</AvatarFallback>
      </Avatar>
    );
  };

  const renderUpgradeButton = () => {
    if (
      isProduction() &&
      !isOnPremise() &&
      isOrganizationAdmin &&
      !isAcademic &&
      !isEnterpriseCustomer &&
      !hideUpgradeButton
    ) {
      return (
        <a
          href={buildUrl(
            `organizations/${organization.id}/billing`,
            workspaceName,
            "&initialOpenUpgradeCard=true",
          )}
          target="_blank"
          rel="noreferrer"
        >
          <Button size="sm" variant="special">
            <Zap className="mr-1.5 size-3.5 shrink-0" />
            Upgrade
          </Button>
        </a>
      );
    }

    return null;
  };

  const renderAppSelector = () => {
    if (isLLMOnlyOrganization) {
      return null;
    }

    return (
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon">
            <Grip className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuLabel>Your apps</DropdownMenuLabel>

          <DropdownMenuGroup>
            <DropdownMenuItem
              className="flex cursor-pointer flex-row gap-3"
              onClick={handleSwitchToEM}
            >
              <span className="flex size-6 items-center justify-center rounded-[6px] bg-[var(--feature-experiment-management)] text-[8px] font-medium text-white">
                EM
              </span>
              <span>Experiment management</span>
            </DropdownMenuItem>

            <DropdownMenuItem className="flex cursor-pointer flex-row gap-3">
              <span className="flex size-6 items-center justify-center rounded-[6px] bg-[var(--feature-llm)] text-[8px] font-medium text-white">
                LLM
              </span>

              <span>LLM Evaluation (Opik)</span>
            </DropdownMenuItem>
          </DropdownMenuGroup>
        </DropdownMenuContent>
      </DropdownMenu>
    );
  };

  const renderInviteMembers = () => {
    if (isCollaboratorsTabEnabled) {
      if (!canInviteMembers) {
        return null;
      }

      return (
        <DropdownMenuSub
          open={isInviteSubmenuOpen}
          onOpenChange={(open) => {
            setIsInviteSubmenuOpen(open);
            if (!open) {
              setInviteSearchQuery("");
            }
          }}
        >
          <DropdownMenuSubTrigger className="cursor-pointer">
            <UserPlus className="mr-2 size-4" />
            <span>Invite members</span>
          </DropdownMenuSubTrigger>
          <DropdownMenuPortal>
            <InviteUsersPopover
              searchQuery={inviteSearchQuery}
              setSearchQuery={setInviteSearchQuery}
              onClose={handleInviteClose}
              asSubContent
            />
          </DropdownMenuPortal>
        </DropdownMenuSub>
      );
    }

    if (inviteMembersURL) {
      return (
        <a href={inviteMembersURL}>
          <DropdownMenuItem className="cursor-pointer">
            <UserPlus className="mr-2 size-4" />
            <span>Invite members</span>
          </DropdownMenuItem>
        </a>
      );
    }

    return null;
  };

  const renderUserMenu = () => {
    return (
      <DropdownMenu>
        <DropdownMenuTrigger asChild>{renderAvatar(true)}</DropdownMenuTrigger>
        <DropdownMenuContent className="w-60" align="end">
          <div className="flex items-center gap-2 px-4 py-2">
            {renderAvatar()}
            <TooltipWrapper content={user.userName}>
              <span className="comet-body-s-accented truncate text-foreground">
                {user.userName}
              </span>
            </TooltipWrapper>
          </div>
          <DropdownMenuSeparator />
          <DropdownMenuGroup>
            <a href={buildUrl("account-settings", workspaceName)}>
              <DropdownMenuItem className="cursor-pointer">
                <Settings className="mr-2 size-4" />
                <span>Account settings</span>
              </DropdownMenuItem>
            </a>
            {isOrganizationAdmin ? (
              <a
                href={buildUrl(
                  `organizations/${workspace?.organizationId}`,
                  workspaceName,
                )}
              >
                <DropdownMenuItem className="cursor-pointer">
                  <Shield className="mr-2 size-4" />
                  <span>{ADMIN_DASHBOARD_LABEL}</span>
                </DropdownMenuItem>
              </a>
            ) : null}
            {organization?.role !== ORGANIZATION_ROLE_TYPE.viewOnly ? (
              <DropdownMenuSub>
                <DropdownMenuSubTrigger className="cursor-pointer">
                  <KeyRound className="mr-2 size-4" />
                  <span>API Key</span>
                </DropdownMenuSubTrigger>
                <DropdownMenuPortal>
                  <DropdownMenuSubContent className="w-60">
                    <div className="flex h-10 items-center justify-between gap-2 px-4">
                      <span className="comet-body-s truncate text-foreground">
                        {maskAPIKey(user.apiKeys[0])}
                      </span>
                      <div className="flex shrink-0 items-center gap-1 text-light-slate">
                        <button
                          className="cursor-pointer rounded p-0.5 hover:text-foreground"
                          onClick={() => {
                            copy(user.apiKeys[0]);
                            toast({
                              description: "Successfully copied API Key",
                            });
                          }}
                        >
                          <Copy className="size-3.5" />
                        </button>
                        <div className="mx-0.5 h-3.5 w-px bg-border" />
                        <a
                          className="cursor-pointer rounded p-0.5 hover:text-foreground"
                          href={buildUrl(
                            "account-settings/apiKeys",
                            workspaceName,
                          )}
                        >
                          <Settings2 className="size-3.5" />
                        </a>
                      </div>
                    </div>
                  </DropdownMenuSubContent>
                </DropdownMenuPortal>
              </DropdownMenuSub>
            ) : null}
            {renderInviteMembers()}
          </DropdownMenuGroup>
          <DropdownMenuSeparator />
          <DropdownMenuGroup>
            <DropdownMenuItem
              onClick={openQuickstart}
              className="cursor-pointer"
            >
              <GraduationCap className="mr-2 size-4" />
              <span>Quickstart guide</span>
            </DropdownMenuItem>
            <a href={buildDocsUrl()} target="_blank" rel="noreferrer">
              <DropdownMenuItem className="cursor-pointer">
                <Book className="mr-2 size-4" />
                <span>Docs</span>
              </DropdownMenuItem>
            </a>
          </DropdownMenuGroup>
          <DropdownMenuSeparator />
          <DropdownMenuGroup>
            <DropdownMenuSub>
              <DropdownMenuSubTrigger className="flex cursor-pointer items-center">
                <CurrentIcon className="mr-2 size-4" />
                <span>Theme</span>
              </DropdownMenuSubTrigger>
              <DropdownMenuPortal>
                <DropdownMenuSubContent>
                  {themeOptions.map(({ value, label, icon: Icon }) => (
                    <DropdownMenuItem
                      key={value}
                      className="cursor-pointer"
                      onClick={() => handleThemeSelect(value)}
                    >
                      <div className="relative flex w-full items-center pl-6">
                        {theme === value && (
                          <Check className="absolute left-0 size-4" />
                        )}
                        <Icon className="mr-2 size-4" />
                        <span>{label}</span>
                      </div>
                    </DropdownMenuItem>
                  ))}
                </DropdownMenuSubContent>
              </DropdownMenuPortal>
            </DropdownMenuSub>
          </DropdownMenuGroup>
          <DateFormatDropdown
            dateFormat={dateFormat}
            setDateFormat={setDateFormat}
          />
          <DropdownMenuItem
            className="cursor-pointer"
            onClick={async () => {
              await api.get("auth/logout");
              const randomCacheNumber = Math.floor(1e8 * Math.random());
              window.location.href = buildUrl(
                "",
                "",
                `&cache=${randomCacheNumber}`,
              );
            }}
          >
            <LogOut className="mr-2 size-4" />
            <span>Logout</span>
          </DropdownMenuItem>
          {APP_VERSION && (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuItem
                className="cursor-pointer justify-center text-light-slate"
                onClick={() => {
                  copy(APP_VERSION);
                  toast({ description: "Successfully copied version" });
                }}
              >
                <span className="comet-body-xs-accented truncate ">
                  VERSION {APP_VERSION}
                </span>
                <Copy className="ml-2 size-3 shrink-0" />
              </DropdownMenuItem>
            </>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    );
  };

  return (
    <div className="flex shrink-0 items-center gap-3">
      {renderUpgradeButton()}
      {renderAppSelector()}
      {renderUserMenu()}
    </div>
  );
};

export default UserMenu;
