import React from "react";
import { MoveLeft } from "lucide-react";
import {
  Link,
  Navigate,
  useMatchRoute,
  useParams,
} from "@tanstack/react-router";

import Loader from "@/components/shared/Loader/Loader";
import { DEFAULT_WORKSPACE_NAME } from "@/constants/user";
import useAppStore from "@/store/AppStore";
import useUser from "./useUser";
import { buildUrl } from "./utils";
import useSegment from "./analytics/useSegment";
import Logo from "./Logo";

type WorkspacePreloaderProps = {
  children: React.ReactNode;
};

const WorkspacePreloader: React.FunctionComponent<WorkspacePreloaderProps> = ({
  children,
}) => {
  const { data: user, isLoading } = useUser();
  const matchRoute = useMatchRoute();
  const workspaceNameFromURL = useParams({
    strict: false,
    select: (params) => params["workspaceName"],
  });
  const isRootPath = matchRoute({ to: "/" });

  useSegment(user?.userName);

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

  const workspace = workspaceNameFromURL
    ? user.getTeams.teams.find((team) => team.teamName === workspaceNameFromURL)
    : null;

  if (workspace) {
    useAppStore.getState().setActiveWorkspaceName(workspace.teamName);
  } else {
    const defaultWorkspace = user.getTeams.teams.find(
      (team) => team.default_team,
    );

    if (defaultWorkspace) {
      if (isRootPath) {
        useAppStore
          .getState()
          .setActiveWorkspaceName(defaultWorkspace.teamName);

        return (
          <Navigate
            to="/$workspaceName"
            params={{ workspaceName: defaultWorkspace.teamName }}
          />
        );
      }

      return (
        <main>
          <nav className="comet-header-height flex w-full items-center justify-between gap-6 border-b">
            <Link
              to="/$workspaceName"
              className="absolute left-[18px] z-10 block"
              params={{ workspaceName: defaultWorkspace.teamName }}
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
              params={{ workspaceName: defaultWorkspace.teamName }}
            >
              <div className="comet-body flex flex-row items-center justify-end text-[#5155F5]">
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

  return children;
};

export default WorkspacePreloader;
