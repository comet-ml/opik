import React from "react";
import { Link } from "@tanstack/react-router";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { useActiveWorkspaceName } from "@/store/AppStore";
import { OnChangeFn } from "@/types/shared";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import { cn } from "@/lib/utils";
import Logo from "@/v2/layout/Logo/Logo";
import usePluginsStore from "@/store/PluginsStore";
import GitHubStarListItem from "@/v2/layout/SideBar/GitHubStarListItem/GitHubStarListItem";
import SideBarMenuItems from "@/v2/layout/SideBar/SideBarMenuItems";
import ProjectSelector from "@/v2/layout/SideBar/ProjectSelector/ProjectSelector";
import WorkspaceSection from "@/v2/layout/SideBar/WorkspaceSection/WorkspaceSection";

const HOME_PATH = "/$workspaceName/home";

type SideBarProps = {
  expanded: boolean;
  setExpanded: OnChangeFn<boolean | undefined>;
};

const SideBar: React.FunctionComponent<SideBarProps> = ({
  expanded,
  setExpanded,
}) => {
  const workspaceName = useActiveWorkspaceName();
  const LogoComponent = usePluginsStore((state) => state.Logo);

  const logo = LogoComponent ? (
    <LogoComponent expanded={expanded} />
  ) : (
    <Logo expanded={expanded} />
  );

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

      <div className="relative flex h-[calc(100%-var(--header-height))] flex-col">
        <Button
          variant="outline"
          size="icon-2xs"
          onClick={() => setExpanded((s) => !s)}
          className={cn(
            "absolute -right-3 top-2 z-50 hidden rounded-full lg:group-hover:flex",
          )}
        >
          {expanded ? <ChevronLeft /> : <ChevronRight />}
        </Button>

        <div className="flex min-h-0 flex-1 flex-col overflow-auto px-3 py-4">
          <ProjectSelector expanded={expanded} />
          <ul className="mt-2 flex flex-col">
            <SideBarMenuItems expanded={expanded} />
          </ul>
        </div>

        <div className="shrink-0 px-3 pb-4">
          <Separator />
          <WorkspaceSection expanded={expanded} />
          <Separator />
          <div className="pt-2">
            <GitHubStarListItem expanded={expanded} />
          </div>
        </div>
      </div>
    </aside>
  );
};

export default SideBar;
