import React from "react";
import { useActiveWorkspaceName } from "@/store/AppStore";
import { Separator } from "@/ui/separator";
import { calculateWorkspaceName, cn } from "@/lib/utils";
import usePluginsStore from "@/store/PluginsStore";
import SidebarMenuItem from "@/v2/layout/SideBar/MenuItem/SidebarMenuItem";
import GitHubStarListItem from "@/v2/layout/SideBar/GitHubStarListItem/GitHubStarListItem";
import BackToProjectButton from "@/v2/layout/SideBar/BackToProjectButton";
import { getWorkspaceSidebarMenuItems } from "@/v2/layout/SideBar/helpers/getMenuItems";
import { usePermissions } from "@/contexts/PermissionsContext";

interface WorkspaceSidebarContentProps {
  expanded: boolean;
}

const WorkspaceSidebarContent: React.FC<WorkspaceSidebarContentProps> = ({
  expanded,
}) => {
  const workspaceName = useActiveWorkspaceName();
  const SidebarWorkspaceSelectorComponent = usePluginsStore(
    (state) => state.SidebarWorkspaceSelector,
  );
  const {
    permissions: { canViewDashboards },
  } = usePermissions();

  const displayName = calculateWorkspaceName(workspaceName);

  const menuGroups = getWorkspaceSidebarMenuItems({ canViewDashboards });

  const initial = (displayName || workspaceName).charAt(0).toUpperCase();

  const workspaceSelector = SidebarWorkspaceSelectorComponent ? (
    <SidebarWorkspaceSelectorComponent expanded={expanded} />
  ) : expanded ? (
    <div className="flex items-center gap-2 px-2 py-1">
      <span className="comet-body-xs-accented flex size-8 shrink-0 items-center justify-center rounded-md bg-muted text-muted-slate">
        {initial}
      </span>
      <div className="flex min-w-0 flex-col">
        <span className="comet-body-xs-accented text-light-slate">
          Workspace
        </span>
        <span className="comet-body-s-accented truncate text-foreground">
          {displayName}
        </span>
      </div>
    </div>
  ) : (
    <span className="comet-body-xs-accented flex size-7 shrink-0 items-center justify-center self-center rounded-md bg-muted text-[10.5px] leading-none text-muted-slate">
      {initial}
    </span>
  );

  return (
    <>
      {workspaceSelector}
      <Separator className={cn("my-2", !expanded && "mx-1 w-auto")} />
      <div className="flex min-h-0 flex-1 flex-col overflow-auto">
        <ul className={cn("flex flex-col", !expanded && "gap-1")}>
          {menuGroups.flatMap((group) =>
            group.items.map((item) => (
              <SidebarMenuItem key={item.id} item={item} expanded={expanded} />
            )),
          )}
        </ul>
      </div>

      <div className="shrink-0 pt-2">
        <BackToProjectButton expanded={expanded} />
        <Separator className={cn("my-2", !expanded && "mx-1 w-auto")} />
        <ul className={cn("flex flex-col", !expanded && "gap-1")}>
          <GitHubStarListItem expanded={expanded} />
        </ul>
      </div>
    </>
  );
};

export default WorkspaceSidebarContent;
