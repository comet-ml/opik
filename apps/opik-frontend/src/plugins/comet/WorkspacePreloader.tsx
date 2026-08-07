import {
  Link,
  Navigate,
  useLocation,
  useMatchRoute,
  useParams,
} from "@tanstack/react-router";
import { MoveLeft } from "lucide-react";
import React, { useEffect } from "react";

import Loader from "@/shared/Loader/Loader";
import { Button } from "@/ui/button";
import { DEFAULT_WORKSPACE_NAME } from "@/constants/user";
import { isLandingRoute } from "@/lib/landingRoutes";
import useLandingWorkspace from "@/plugins/comet/useLandingWorkspace";
import useWorkspaceByName from "@/plugins/comet/useWorkspaceByName";
import useAppStore, { useSetAppUser } from "@/store/AppStore";
import { usePostHog } from "posthog-js/react";
import Logo from "@/shared/Logo/Logo";
import { identifyReoUser } from "./analytics/reo";
import useSegment from "./analytics/useSegment";
import { ORGANIZATION_ROLE_TYPE, Organization, Workspace } from "./types";
import { isHiddenSpendWorkspace } from "./lib/aiSpend";
import useOrganizations from "./useOrganizations";
import useUser from "./useUser";
import { buildUrl } from "./utils";

type WorkspacePreloaderProps = {
  children: React.ReactNode;
};

const hasWorkspaceAccess = (
  workspace: Workspace,
  organizations: Organization[],
): boolean => {
  const workspaceOrganization = organizations?.find(
    (organization) => organization.id === workspace.organizationId,
  );

  return workspaceOrganization?.role !== ORGANIZATION_ROLE_TYPE.emAndMPMOnly;
};

const redirectToEM = () => {
  window.location.href = buildUrl("");
};

// Dedicated key (not redirectURLAfterLogin, which the SSO modal overwrites) so the
// MCP OAuth return survives both email and SSO logins. comet-react consumes it post-login.
const MCP_OAUTH_REDIRECT_URL_KEY = "mcpOAuthRedirectURL";

// Persist the MCP OAuth authorize URL (carried as ?returnTo= by opik-backend) before
// bouncing to login, so the user returns there after authenticating.
const persistMcpOAuthReturn = () => {
  const returnTo = new URLSearchParams(window.location.search).get("returnTo");
  if (!returnTo) return;
  try {
    if (new URL(returnTo).origin === window.location.origin) {
      window.localStorage.setItem(MCP_OAUTH_REDIRECT_URL_KEY, returnTo);
    }
  } catch {
    // ignore a malformed returnTo
  }
};

const WorkspacePreloader: React.FunctionComponent<WorkspacePreloaderProps> = ({
  children,
}) => {
  const setAppUser = useSetAppUser();
  const { data: user, isLoading } = useUser();

  const { data: organizations } = useOrganizations({
    enabled: !!user?.loggedIn,
  });

  const matchRoute = useMatchRoute();
  const workspaceNameFromURL = useParams({
    strict: false,
    select: (params) => params["workspaceName"],
  });

  // Point reads instead of the whole organization: the workspace named in the URL, and -- only if
  // that one turns out not to be visible -- the user's own default workspace to fall back to. Its
  // name comes from the user payload, so neither lookup needs a list (OPIK-7699).
  const {
    data: urlWorkspace,
    isPending: isUrlWorkspacePending,
    isError: isUrlWorkspaceError,
  } = useWorkspaceByName(
    { workspaceName: workspaceNameFromURL ?? "" },
    { enabled: !!user?.loggedIn && !!workspaceNameFromURL },
  );
  const needsFallbackWorkspace =
    !!user?.loggedIn && (!workspaceNameFromURL || urlWorkspace === null);
  const {
    data: userDefaultWorkspace,
    isPending: isDefaultWorkspacePending,
    isError: isDefaultWorkspaceError,
  } = useWorkspaceByName(
    { workspaceName: user?.defaultWorkspace ?? "" },
    { enabled: needsFallbackWorkspace && !!user?.defaultWorkspace },
  );
  // Last resort, for a user whose default workspace no longer resolves: ask the backend where they
  // land in their organization. Replaces the old "first row of the unordered list" fallback.
  const needsLandingWorkspace =
    needsFallbackWorkspace &&
    (!user?.defaultWorkspace ||
      userDefaultWorkspace === null ||
      isDefaultWorkspaceError);
  const {
    data: landingWorkspace,
    isPending: isLandingWorkspacePending,
    isError: isLandingWorkspaceError,
  } = useLandingWorkspace(
    { organizationId: organizations?.[0]?.id ?? "" },
    { enabled: needsLandingWorkspace && !!organizations?.[0]?.id },
  );
  const { pathname } = useLocation();
  const isRootPath = matchRoute({ to: "/" });

  useSegment(user?.userName);

  const posthog = usePostHog();
  useEffect(() => {
    if (!user?.loggedIn) {
      return;
    }

    setAppUser({
      apiKey: user.apiKeys[0],
      userName: user.userName,
      email: user.email,
    });

    posthog?.identify(user.userName, {
      email: user.email,
    });

    // Reo.Dev user identification for usage tracking
    // Prefer GitHub handle if available, otherwise use email
    const organization = organizations?.find(
      (org) => org.id === urlWorkspace?.organizationId,
    );

    if (user.gitHub) {
      identifyReoUser({
        username: user.userName,
        type: "github",
        other_identities: [
          {
            username: user.email,
            type: "email",
          },
        ],
        company: organization?.name,
      });
    } else {
      identifyReoUser({
        username: user.email,
        type: "email",
        company: organization?.name,
      });
    }
  }, [
    posthog,
    user?.loggedIn,
    user?.userName,
    user?.email,
    user?.apiKeys,
    user?.gitHub,
    urlWorkspace,
    organizations,
    workspaceNameFromURL,
    setAppUser,
  ]);

  if (isLoading) {
    return <Loader />;
  }

  if (!user || !user.loggedIn) {
    persistMcpOAuthReturn();
    window.location.href =
      workspaceNameFromURL === DEFAULT_WORKSPACE_NAME || !workspaceNameFromURL
        ? buildUrl("login")
        : buildUrl("login", workspaceNameFromURL);
    return null;
  }

  if (
    isLandingRoute(pathname) &&
    workspaceNameFromURL &&
    user.defaultWorkspace === workspaceNameFromURL &&
    !user.suspended
  ) {
    useAppStore.getState().setActiveWorkspaceName(workspaceNameFromURL);
    return children;
  }

  // A failure is not an answer: hold the loader rather than telling the user their workspace is
  // private, which is what the list-based version did while it had no data either.
  if (
    (workspaceNameFromURL && (isUrlWorkspacePending || isUrlWorkspaceError)) ||
    (needsFallbackWorkspace &&
      user.defaultWorkspace &&
      isDefaultWorkspacePending) ||
    (needsLandingWorkspace &&
      organizations?.[0] &&
      (isLandingWorkspacePending || isLandingWorkspaceError))
  ) {
    return <Loader />;
  }

  const matchedWorkspace = workspaceNameFromURL ? urlWorkspace : null;

  // Hidden spend workspace resolves as "not found" → private-project message.
  const workspace = isHiddenSpendWorkspace(matchedWorkspace, pathname)
    ? null
    : matchedWorkspace;

  if (workspace) {
    if (organizations && !hasWorkspaceAccess(workspace, organizations)) {
      redirectToEM();
      return null;
    }

    useAppStore.getState().setActiveWorkspaceName(workspace.workspaceName);
  } else {
    const defaultWorkspace = userDefaultWorkspace ?? landingWorkspace;

    if (defaultWorkspace) {
      if (
        organizations &&
        !hasWorkspaceAccess(defaultWorkspace, organizations)
      ) {
        redirectToEM();
        return null;
      }

      if (isRootPath) {
        useAppStore
          .getState()
          .setActiveWorkspaceName(defaultWorkspace.workspaceName);

        return (
          <Navigate
            to="/$workspaceName"
            params={{ workspaceName: defaultWorkspace.workspaceName }}
          />
        );
      }

      return (
        <main>
          <nav className="comet-header-height flex w-full items-center justify-between gap-6 border-b">
            <Link
              to="/$workspaceName"
              className="absolute left-[18px] z-10 block"
              params={{ workspaceName: defaultWorkspace.workspaceName }}
            >
              <Logo expanded />
            </Link>
          </nav>

          <div className="flex flex-col items-center gap-4 px-10 py-24">
            <div className="comet-title-m text-muted-slate">
              This is a private project
            </div>
            <Link
              to="/$workspaceName"
              params={{ workspaceName: defaultWorkspace.workspaceName }}
            >
              <div className="comet-body flex flex-row items-center justify-end text-[hsl(var(--primary))]">
                <MoveLeft className="mr-2 size-4" /> Go back to your workspace
              </div>
            </Link>
          </div>
        </main>
      );
    }

    window.location.href = buildUrl("login");
    return null;
  }

  if (user.orgReachedTraceLimit) {
    return (
      <div className="flex h-screen w-screen flex-col items-center justify-center gap-4">
        <div className="comet-body py-4">
          Opik traces limit has reached, to continue please purchase additional
          traces via AWS
        </div>

        <Button variant="secondary" onClick={() => window.location.reload()}>
          Refresh page
        </Button>
      </div>
    );
  }

  return children;
};

export default WorkspacePreloader;
