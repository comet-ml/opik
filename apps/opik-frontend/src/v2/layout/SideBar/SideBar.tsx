import React from "react";
import { Link } from "@tanstack/react-router";
import { PanelLeftClose } from "lucide-react";
import { useActiveWorkspaceName } from "@/store/AppStore";
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
  pinned: boolean;
  onTogglePin: () => void;
  overlayOpen: boolean;
  onOverlayOpenChange: (open: boolean) => void;
  isMobile: boolean;
};

const SideBar: React.FunctionComponent<SideBarProps> = ({
  pinned,
  onTogglePin,
  overlayOpen,
  onOverlayOpenChange,
  isMobile,
}) => {
  const workspaceName = useActiveWorkspaceName();
  const LogoComponent = usePluginsStore((state) => state.Logo);

  const logo = LogoComponent ? (
    <LogoComponent expanded={true} />
  ) : (
    <Logo expanded={true} />
  );

  const sidebarContent = (
    <>
      <div className="comet-header-height relative flex w-full items-center justify-between gap-6 border-b">
        <Link
          to={HOME_PATH}
          className="absolute left-[18px] z-10 block"
          params={{ workspaceName }}
        >
          {logo}
        </Link>
        {(pinned || isMobile) && (
          <Button
            variant="ghost"
            size="icon-xs"
            onClick={isMobile ? () => onOverlayOpenChange(false) : onTogglePin}
            className="absolute right-2 z-10"
          >
            <PanelLeftClose className="size-4" />
          </Button>
        )}
      </div>

      <div className="relative flex h-[calc(100%-var(--header-height))] flex-col">
        <div className="flex min-h-0 flex-1 flex-col overflow-auto px-3 py-4">
          <ProjectSelector />
          <ul className="mt-2 flex flex-col">
            <SideBarMenuItems />
          </ul>
        </div>

        <div className="shrink-0 px-3 pb-4">
          <Separator />
          <WorkspaceSection />
          <Separator />
          <div className="pt-2">
            <GitHubStarListItem />
          </div>
        </div>
      </div>
    </>
  );

  if (pinned) {
    return (
      <aside className="comet-sidebar-width h-[calc(100vh-var(--banner-height))] border-r transition-all">
        {sidebarContent}
      </aside>
    );
  }

  return (
    <>
      {!isMobile && !overlayOpen && (
        <div
          className="fixed left-0 top-0 z-50 h-screen w-[10px]"
          onMouseEnter={() => onOverlayOpenChange(true)}
        />
      )}

      {isMobile && overlayOpen && (
        <div
          className="fixed inset-0 z-40"
          onClick={() => onOverlayOpenChange(false)}
        />
      )}

      <aside
        className={cn(
          "fixed left-0 top-0 z-50 h-screen w-[240px] border-r bg-background shadow-lg transition-transform duration-200",
          overlayOpen ? "translate-x-0" : "-translate-x-full",
        )}
        onMouseLeave={isMobile ? undefined : () => onOverlayOpenChange(false)}
      >
        {sidebarContent}
      </aside>
    </>
  );
};

export default SideBar;
