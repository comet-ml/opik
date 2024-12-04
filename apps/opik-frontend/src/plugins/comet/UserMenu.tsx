import { Link, useNavigate } from "@tanstack/react-router";
import copy from "clipboard-copy";
import sortBy from "lodash/sortBy";
import {
  Book,
  Copy,
  GraduationCap,
  Grip,
  KeyRound,
  LogOut,
  Settings,
  Shield,
  UserPlus,
} from "lucide-react";

import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
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
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { useToast } from "@/components/ui/use-toast";
import { buildDocsUrl, cn, maskAPIKey } from "@/lib/utils";
import useAppStore from "@/store/AppStore";
import api from "./api";
import { Organization, ORGANIZATION_ROLE_TYPE } from "./types";
import useOrganizations from "./useOrganizations";
import useUser from "./useUser";
import useUserPermissions from "./useUserPermissions";
import { buildUrl } from "./utils";
import { APP_VERSION } from "@/constants/app";

const UserMenu = () => {
  const navigate = useNavigate();
  const { toast } = useToast();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data: user } = useUser();
  const { data: organizations, isLoading } = useOrganizations({
    enabled: !!user?.loggedIn,
  });

  const workspace = user?.getTeams.teams.find(
    (team) => team.teamName === workspaceName,
  );

  const { data: userPermissions } = useUserPermissions(
    {
      userName: user?.userName || "",
      organizationId: workspace?.organizationId || "",
    },
    { enabled: !!user?.loggedIn && !!workspace },
  );

  if (
    !user ||
    !user.loggedIn ||
    isLoading ||
    !organizations ||
    !userPermissions
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
  const organizationTeams = user.getTeams.teams.filter(
    (team) => team.organizationId === organization?.id,
  );
  const isOrganizationAdmin =
    organization?.role === ORGANIZATION_ROLE_TYPE.admin;
  const workspacePermissions = userPermissions.find(
    (userPermission) => userPermission.workspaceName === workspaceName,
  );
  const invitePermission = workspacePermissions?.permissions.find(
    (permission) => permission.permissionName === "invite_users_to_workspace",
  );
  const canInviteMembers =
    isOrganizationAdmin || invitePermission?.permissionValue === "true";

  const handleChangeOrganization = (newOrganization: Organization) => {
    const newOrganizationTeams = user.getTeams.teams.filter(
      (team) => team.organizationId === newOrganization.id,
    );

    const newWorkspace =
      newOrganizationTeams.find((team) => team.default_team) ||
      newOrganizationTeams[0];

    if (newWorkspace) {
      navigate({
        to: "/$workspaceName",
        params: { workspaceName: newWorkspace.teamName },
      });
    }
  };

  const renderAvatar = (clickable = false) => {
    return (
      <Avatar className={cn(clickable ? "cursor-pointer" : "")}>
        <AvatarImage src={user.profileImages.small} />
        <AvatarFallback>{user.userName.charAt(0).toUpperCase()}</AvatarFallback>
      </Avatar>
    );
  };

  const renderAppSelector = () => {
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
              <span className="flex size-6 items-center justify-center rounded-[6px] bg-[#6C6FF7] text-[8px] font-medium text-white">
                EM
              </span>
              <span>Experiment management</span>
            </DropdownMenuItem>

            <DropdownMenuItem className="flex cursor-pointer flex-row gap-3">
              <span className="flex size-6 items-center justify-center rounded-[6px] bg-[#52AEA4] text-[8px] font-medium text-white">
                LLM
              </span>

              <span>LLM Evaluation (Opik)</span>
            </DropdownMenuItem>
          </DropdownMenuGroup>
        </DropdownMenuContent>
      </DropdownMenu>
    );
  };

  const renderUserMenu = () => {
    return (
      <DropdownMenu>
        <DropdownMenuTrigger asChild>{renderAvatar(true)}</DropdownMenuTrigger>
        <DropdownMenuContent className="w-60" align="end">
          <div className="flex items-center gap-2 px-4 py-2">
            {renderAvatar()}
            <TooltipWrapper content={user.userName}>
              <span className="comet-body-s-accented truncate text-secondary-foreground">
                {user.userName}
              </span>
            </TooltipWrapper>
          </div>
          <DropdownMenuSeparator />
          <DropdownMenuGroup>
            <DropdownMenuSub>
              <DropdownMenuSubTrigger className="cursor-pointer">
                <span className="comet-body-s-accented pr-1">Workspace:</span>
                <span className="comet-body-s truncate">{workspaceName}</span>
              </DropdownMenuSubTrigger>
              <DropdownMenuPortal>
                <DropdownMenuSubContent className="w-60">
                  <div className="max-h-[200px] overflow-auto">
                    {sortBy(organizationTeams, "teamName").map((team) => (
                      <Link key={team.teamName} to={`/${team.teamName}`}>
                        <DropdownMenuCheckboxItem
                          checked={workspaceName === team.teamName}
                        >
                          <TooltipWrapper content={team.teamName}>
                            <span className="truncate">{team.teamName}</span>
                          </TooltipWrapper>
                        </DropdownMenuCheckboxItem>
                      </Link>
                    ))}
                  </div>
                  <DropdownMenuSeparator />
                  <a
                    className="flex justify-center"
                    href={buildUrl(
                      "account-settings/workspaces",
                      workspaceName,
                    )}
                  >
                    <Button variant="link">View all workspaces</Button>
                  </a>
                </DropdownMenuSubContent>
              </DropdownMenuPortal>
            </DropdownMenuSub>
          </DropdownMenuGroup>
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
                  <span>Admin Dashboard</span>
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
                    <DropdownMenuItem
                      className="cursor-pointer"
                      onClick={() => {
                        copy(user.apiKeys[0]);
                        toast({ description: "Successfully copied API Key" });
                      }}
                    >
                      <span>{maskAPIKey(user.apiKeys[0])}</span>
                      <Copy className="ml-2 size-3 shrink-0" />
                    </DropdownMenuItem>
                    <DropdownMenuSeparator />
                    <a
                      className="comet-body-s flex justify-center"
                      href={buildUrl("account-settings/apiKeys", workspaceName)}
                    >
                      <Button variant="link">Manage API keys</Button>
                    </a>
                  </DropdownMenuSubContent>
                </DropdownMenuPortal>
              </DropdownMenuSub>
            ) : null}
            {canInviteMembers ? (
              <a
                href={buildUrl(
                  "account-settings/workspaces",
                  workspaceName,
                  `&initialInviteId=${workspace?.teamId}`,
                )}
              >
                <DropdownMenuItem className="cursor-pointer">
                  <UserPlus className="mr-2 size-4" />
                  <span>Invite members</span>
                </DropdownMenuItem>
              </a>
            ) : null}
          </DropdownMenuGroup>
          <DropdownMenuSeparator />
          <DropdownMenuGroup>
            <Link to="/$workspaceName/quickstart" params={{ workspaceName }}>
              <DropdownMenuItem className="cursor-pointer">
                <GraduationCap className="mr-2 size-4" />
                <span>Quickstart guide</span>
              </DropdownMenuItem>
            </Link>
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
                <span className="mr-2 mt-px flex size-4 items-center justify-center rounded border border-black text-xs">
                  {organization?.name.charAt(0).toUpperCase()}
                </span>
                <span className="comet-body-s truncate">
                  {organization?.name}
                </span>
              </DropdownMenuSubTrigger>
              <DropdownMenuPortal>
                <DropdownMenuSubContent className="w-60">
                  <div className="max-h-[200px] overflow-auto">
                    {sortBy(organizations, "name").map((org) => (
                      <DropdownMenuCheckboxItem
                        checked={organization?.name === org.name}
                        key={org.name}
                        onClick={() => handleChangeOrganization(org)}
                      >
                        <TooltipWrapper content={org.name}>
                          <span className="truncate">{org.name}</span>
                        </TooltipWrapper>
                      </DropdownMenuCheckboxItem>
                    ))}
                  </div>
                </DropdownMenuSubContent>
              </DropdownMenuPortal>
            </DropdownMenuSub>
          </DropdownMenuGroup>
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
          {APP_VERSION ? (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuItem
                className="cursor-pointer justify-center text-muted-slate"
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
          ) : null}
        </DropdownMenuContent>
      </DropdownMenu>
    );
  };

  return (
    <div className="flex shrink-0 items-center gap-4">
      {renderAppSelector()}
      {renderUserMenu()}
    </div>
  );
};

export default UserMenu;
