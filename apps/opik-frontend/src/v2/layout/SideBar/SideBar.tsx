import React from "react";
import { Link, useRouterState } from "@tanstack/react-router";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { useActiveWorkspaceName } from "@/store/AppStore";
import { OnChangeFn } from "@/types/shared";
import { Button } from "@/ui/button";
import { cn } from "@/lib/utils";
import Logo from "@/shared/Logo/Logo";
import { useActiveProjectInitializer } from "@/hooks/useActiveProjectInitializer";
import ProjectSidebarContent from "@/v2/layout/SideBar/ProjectSidebarContent";
import WorkspaceSidebarContent from "@/v2/layout/SideBar/WorkspaceSidebarContent";

const HOME_PATH = "/$workspaceName/home";

type SideBarProps = {
  expanded: boolean;
  setExpanded: OnChangeFn<boolean | undefined>;
};

const SideBar: React.FunctionComponent<SideBarProps> = ({
  expanded,
  setExpanded,
}) => {
  useActiveProjectInitializer();
  const workspaceName = useActiveWorkspaceName();

  const isProjectRoute = useRouterState({
    select: (state) =>
      state.matches.some((match) => "projectId" in match.params),
  });

  const logo = <Logo expanded={expanded} />;

  return (
    <aside className="comet-sidebar-width group h-[calc(100vh-var(--banner-height))] border-r transition-all">
      <div className="comet-header-height relative flex w-full items-center justify-between gap-6 border-b">
        <Link
          to={HOME_PATH}
          className="absolute left-[18px] z-10 block"
          params={{ workspaceName }}
        >
          {logo}
        </Link>
      </div>
      <div className="relative flex h-[calc(100%-var(--header-height))]">
        <Button
          variant="outline"
          size="icon-2xs"
          onClick={() => setExpanded((s) => !s)}
          className={cn(
            "absolute -right-3 top-2 hidden rounded-full z-50 lg:group-hover:flex",
          )}
        >
          {expanded ? <ChevronLeft /> : <ChevronRight />}
        </Button>
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
