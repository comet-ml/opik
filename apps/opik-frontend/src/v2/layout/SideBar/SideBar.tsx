import React from "react";
import { Link } from "@tanstack/react-router";
import { PanelLeft } from "lucide-react";
import useAppStore, { useActiveWorkspaceName } from "@/store/AppStore";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import { cn, calculateWorkspaceName } from "@/lib/utils";
import Logo from "@/shared/Logo/Logo";
import usePluginsStore from "@/store/PluginsStore";
import SideBarMenuItems from "@/v2/layout/SideBar/SideBarMenuItems";
import ProjectSelector from "@/v2/layout/SideBar/ProjectSelector/ProjectSelector";
import SidebarMenuItem from "@/v2/layout/SideBar/MenuItem/SidebarMenuItem";
import { getWorkspaceMenuItems } from "@/v2/layout/SideBar/helpers/getMenuItems";
import { usePermissions } from "@/contexts/PermissionsContext";

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
  const SidebarWorkspaceSelectorComponent = usePluginsStore(
    (state) => state.SidebarWorkspaceSelector,
  );
  const {
    permissions: { canViewDashboards },
  } = usePermissions();

  const menuGroups = getWorkspaceMenuItems({ canViewDashboards });
  const displayName = calculateWorkspaceName(
    useAppStore((state) => state.activeWorkspaceName),
  );

  const logo = <Logo expanded={true} />;

  const workspaceSelector = SidebarWorkspaceSelectorComponent ? (
    <SidebarWorkspaceSelectorComponent />
  ) : (
    <div className="comet-body-s-accented truncate rounded-md px-2 py-1 text-foreground">
      {displayName}
    </div>
  );

  const sidebarContent = (
    <>
      <div className="comet-header-height relative flex w-full items-center justify-between gap-6 border-b">
        <Link
          to={HOME_PATH}
          className="absolute left-4 z-10 block"
          params={{ workspaceName }}
        >
          {logo}
        </Link>
        {(pinned || isMobile) && (
          <Button
            variant="ghost"
            size="icon-2xs"
            onClick={isMobile ? () => onOverlayOpenChange(false) : onTogglePin}
            className="absolute right-3 z-10 pr-[4px]"
          >
            <PanelLeft className="size-4" />
          </Button>
        )}
      </div>

      <div className="relative flex h-[calc(100%-var(--header-height))] flex-col p-3">
        <div className="flex min-h-0 flex-1 flex-col overflow-auto">
          <ProjectSelector />
          <ul className="mt-2 flex flex-col">
            <SideBarMenuItems />
          </ul>
        </div>

        <div className="shrink-0 gap-y-1">
          <Separator className="my-1" />
          <div className="mt-1 flex flex-col">
            <div className="comet-body-xs truncate px-2 py-1 text-light-slate">
              Workspace
            </div>
            {workspaceSelector}
            <ul className="flex flex-col text-muted-slate">
              {menuGroups.flatMap((group) =>
                group.items.map((item) => (
                  <SidebarMenuItem key={item.id} item={item} />
                )),
              )}
            </ul>
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
