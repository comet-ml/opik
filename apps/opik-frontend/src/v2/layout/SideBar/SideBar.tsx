import React from "react";
import { Link, useRouterState } from "@tanstack/react-router";
import { PanelLeft } from "lucide-react";
import { useActiveWorkspaceName } from "@/store/AppStore";
import { Button } from "@/ui/button";
import Logo from "@/shared/Logo/Logo";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { useActiveProjectInitializer } from "@/hooks/useActiveProjectInitializer";
import ProjectSidebarContent from "@/v2/layout/SideBar/ProjectSidebarContent";
import WorkspaceSidebarContent from "@/v2/layout/SideBar/WorkspaceSidebarContent";

const HOME_PATH = "/$workspaceName/home";

type SideBarProps = {
  expanded: boolean;
  canToggle: boolean;
  onToggle: () => void;
};

const SideBar: React.FunctionComponent<SideBarProps> = ({
  expanded,
  canToggle,
  onToggle,
}) => {
  useActiveProjectInitializer();
  const workspaceName = useActiveWorkspaceName();

  const isProjectRoute = useRouterState({
    select: (state) =>
      state.matches.some((match) => "projectId" in match.params),
  });

  const logo = <Logo expanded={expanded} />;

  return (
    <aside className="comet-sidebar-width relative h-[calc(100vh-var(--banner-height))] border-r transition-all">
      <div className="comet-header-height relative flex w-full items-center justify-end border-b pr-2">
        <Link
          to={HOME_PATH}
          className="absolute left-[15px] top-1/2 block -translate-y-1/2"
          params={{ workspaceName }}
        >
          {logo}
          {canToggle && !expanded && (
            <TooltipWrapper content="Expand sidebar" side="right">
              <Button
                variant="outline"
                size="icon-4xs"
                onClick={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  onToggle();
                }}
                className="absolute bottom-[-9px] right-[-9px] z-10 text-foreground-secondary shadow-sm"
                aria-label="Expand sidebar"
              >
                <PanelLeft />
              </Button>
            </TooltipWrapper>
          )}
        </Link>
        {canToggle && expanded && (
          <TooltipWrapper content="Collapse sidebar" side="right">
            <Button
              variant="minimal"
              size="icon-xs"
              onClick={onToggle}
              className="text-light-slate duration-100 animate-in fade-in"
              aria-label="Collapse sidebar"
            >
              <PanelLeft />
            </Button>
          </TooltipWrapper>
        )}
      </div>
      <div className="relative flex h-[calc(100%-var(--header-height))]">
        <div className="flex min-h-0 grow flex-col justify-between overflow-auto p-3">
          {isProjectRoute ? (
            <ProjectSidebarContent expanded={expanded} />
          ) : (
            <WorkspaceSidebarContent expanded={expanded} />
          )}
        </div>
      </div>
    </aside>
  );
};

export default SideBar;
