import React from "react";
import { useActiveWorkspaceName } from "@/store/AppStore";
import { Separator } from "@/ui/separator";
import { calculateWorkspaceName } from "@/lib/utils";
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

  const workspaceSelector = SidebarWorkspaceSelectorComponent ? (
    <SidebarWorkspaceSelectorComponent expanded={expanded} />
  ) : expanded ? (
    <div className="comet-body-s-accented truncate rounded-md px-2 py-1 text-foreground">
      {displayName}
    </div>
  ) : null;

  return (
    <>
      <div className="flex min-h-0 flex-1 flex-col overflow-auto">
        {expanded && (
          <div className="comet-body-xs-accented truncate px-2 py-1 text-light-slate">
            Workspace
          </div>
        )}
        {workspaceSelector}
        <ul className="mt-2 flex flex-col">
          {menuGroups.flatMap((group) =>
            group.items.map((item) => (
              <SidebarMenuItem key={item.id} item={item} expanded={expanded} />
            )),
          )}
        </ul>
      </div>

      <div className="shrink-0 pt-2">
        <BackToProjectButton expanded={expanded} />
        <Separator className="my-4" />
        <ul className="flex flex-col">
          <GitHubStarListItem expanded={expanded} />
        </ul>
      </div>
    </>
  );
};

export default WorkspaceSidebarContent;
