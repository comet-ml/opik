import React from "react";
import useAppStore from "@/store/AppStore";
import usePluginsStore from "@/store/PluginsStore";
import SidebarMenuItem from "@/v2/layout/SideBar/MenuItem/SidebarMenuItem";
import { getWorkspaceMenuItems } from "@/v2/layout/SideBar/helpers/getMenuItems";
import { usePermissions } from "@/contexts/PermissionsContext";
import { calculateWorkspaceName } from "@/lib/utils";

const WorkspaceSection: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const SidebarWorkspaceSelectorComponent = usePluginsStore(
    (state) => state.SidebarWorkspaceSelector,
  );
  const {
    permissions: { canViewDashboards },
  } = usePermissions();

  const menuGroups = getWorkspaceMenuItems({ canViewDashboards });
  const displayName = calculateWorkspaceName(workspaceName);

  const renderWorkspaceSelector = () => {
    if (SidebarWorkspaceSelectorComponent) {
      return <SidebarWorkspaceSelectorComponent />;
    }

    return (
      <div className="comet-body-s-accented truncate rounded-md px-2 py-1 text-foreground">
        {displayName}
      </div>
    );
  };

  return (
    <div className="flex flex-col px-3 py-2">
      <div className="comet-body-s-accented truncate px-2 py-1 text-light-slate">
        Workspace
      </div>

      {renderWorkspaceSelector()}

      <ul className="mt-2 flex flex-col text-muted-slate">
        {menuGroups.flatMap((group) =>
          group.items.map((item) => (
            <SidebarMenuItem key={item.id} item={item} />
          )),
        )}
      </ul>
    </div>
  );
};

export default WorkspaceSection;
