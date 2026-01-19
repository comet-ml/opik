import {
  Link,
  Navigate,
  useMatchRoute,
  useParams,
} from "@tanstack/react-router";
import { MoveLeft } from "lucide-react";
import React, { useEffect } from "react";

import PartialPageLayout from "@/components/layout/PartialPageLayout/PartialPageLayout";
import Loader from "@/components/shared/Loader/Loader";
import { Button } from "@/components/ui/button";
import { DEFAULT_WORKSPACE_NAME } from "@/constants/user";
import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import useAppStore, { useSetAppUser } from "@/store/AppStore";
import { usePostHog } from "posthog-js/react";
import Logo from "./Logo";
import { identifyReoUser } from "./analytics/reo";
import useSegment from "./analytics/useSegment";
import { ORGANIZATION_ROLE_TYPE, Organization, Workspace } from "./types";
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

const WorkspacePreloader: React.FunctionComponent<WorkspacePreloaderProps> = ({
  children,
}) => {
  const setAppUser = useSetAppUser();
  const { data: user, isLoading } = useUser();

  const { data: allWorkspaces } = useAllWorkspaces({
    enabled: !!user?.loggedIn,
  });

  const { data: organizations } = useOrganizations({
    enabled: !!user?.loggedIn,
  });

  const matchRoute = useMatchRoute();
  const workspaceNameFromURL = useParams({
    strict: false,
    select: (params) => params["workspaceName"],
  });
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
    });

    posthog?.identify(user.userName, {
      email: user.email,
    });

    // Reo.Dev user identification for usage tracking
    // Prefer GitHub handle if available, otherwise use email
    const workspace = allWorkspaces?.find(
      (ws) => ws.workspaceName === workspaceNameFromURL,
    );
    const organization = organizations?.find(
      (org) => org.id === workspace?.organizationId,
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
    allWorkspaces,
    organizations,
    workspaceNameFromURL,
    setAppUser,
  ]);

  if (isLoading) {
    return <Loader />;
  }

  if (!user || !user.loggedIn) {
    window.location.href =
      workspaceNameFromURL === DEFAULT_WORKSPACE_NAME || !workspaceNameFromURL
        ? buildUrl("login")
        : buildUrl("login", workspaceNameFromURL);
    return null;
  }

  if (!allWorkspaces) {
    return <Loader />;
  }

  const workspace = workspaceNameFromURL
    ? allWorkspaces.find(
        (workspace) => workspace.workspaceName === workspaceNameFromURL,
      )
    : null;

  if (workspace) {
    if (organizations && !hasWorkspaceAccess(workspace, organizations)) {
      redirectToEM();
      return null;
    }

    useAppStore.getState().setActiveWorkspaceName(workspace.workspaceName);
  } else {
    const defaultWorkspace =
      allWorkspaces.find((workspace) => workspace.default) ??
      allWorkspaces?.[0];

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
      <PartialPageLayout>
        <div className="flex flex-col items-center gap-4 px-10 py-24">
          <div className="comet-body py-4">
            Opik traces limit has reached, to continue please purchase
            additional traces via AWS
          </div>

          <Button variant="secondary" onClick={() => window.location.reload()}>
            Refresh page
          </Button>
        </div>
      </PartialPageLayout>
    );
  }

  return children;
};

export default WorkspacePreloader;
