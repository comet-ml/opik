import React from "react";
import { useActiveProjectId } from "@/store/AppStore";
import usePluginsStore from "@/store/PluginsStore";
import { Separator } from "@/ui/separator";
import SidebarMenuItem, {
  MenuItem,
} from "@/v2/layout/SideBar/MenuItem/SidebarMenuItem";
import getMenuItems from "@/v2/layout/SideBar/helpers/getMenuItems";
import { usePermissions } from "@/contexts/PermissionsContext";

interface SideBarMenuItemsProps {
  expanded: boolean;
}

const SideBarMenuItems: React.FC<SideBarMenuItemsProps> = ({ expanded }) => {
  const activeProjectId = useActiveProjectId();
  const AssistantSidebar = usePluginsStore((state) => state.AssistantSidebar);
  const {
    permissions: {
      canViewExperiments,
      canViewDatasets,
      canViewDashboards,
      canUsePlayground,
      canViewOptimizationRuns,
    },
  } = usePermissions();

  const menuItems = getMenuItems({
    projectId: activeProjectId,
    canViewExperiments,
    canViewDatasets,
    canViewDashboards,
    canUsePlayground,
    canViewOptimizationRuns,
    showHome: !!AssistantSidebar,
  });

  const renderItems = (items: MenuItem[]) => {
    return items.map((item) => (
      <SidebarMenuItem key={item.id} item={item} expanded={expanded} />
    ));
  };

  return (
    <>
      {menuItems.map((menuGroup, index) => (
        <li key={menuGroup.id} className={expanded ? "pb-3" : "pb-1"}>
          {menuGroup.label &&
            (expanded ? (
              <div className="comet-body-xs-accented truncate px-2 py-1 text-light-slate">
                {menuGroup.label}
              </div>
            ) : (
              <div className="py-1">
                {index > 0 && <Separator className="mx-1 w-auto" />}
              </div>
            ))}
          <ul className="flex flex-col text-foreground">
            {renderItems(menuGroup.items)}
          </ul>
        </li>
      ))}
    </>
  );
};

export default SideBarMenuItems;
