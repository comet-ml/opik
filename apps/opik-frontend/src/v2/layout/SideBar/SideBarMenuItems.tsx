import React from "react";
import { useActiveProjectId } from "@/store/AppStore";
import SidebarMenuItem, {
  MenuItem,
} from "@/v2/layout/SideBar/MenuItem/SidebarMenuItem";
import getMenuItems from "@/v2/layout/SideBar/helpers/getMenuItems";
import { usePermissions } from "@/contexts/PermissionsContext";
import { Separator } from "@/ui/separator";

const SideBarMenuItems: React.FC = () => {
  const activeProjectId = useActiveProjectId();
  const {
    permissions: { canViewExperiments, canViewDatasets },
  } = usePermissions();

  const menuItems = getMenuItems({
    projectId: activeProjectId,
    canViewExperiments,
    canViewDatasets,
  });

  const renderItems = (items: MenuItem[]) => {
    return items.map((item) => <SidebarMenuItem key={item.id} item={item} />);
  };

  return (
    <>
      {menuItems.map((menuGroup, index) => (
        <li key={menuGroup.id}>
          {index > 0 && <Separator className="my-2" />}
          {menuGroup.label && (
            <div className="comet-body-s-accented truncate px-2 py-1 text-light-slate">
              {menuGroup.label}
            </div>
          )}
          <ul className="flex flex-col text-foreground">
            {renderItems(menuGroup.items)}
          </ul>
        </li>
      ))}
    </>
  );
};

export default SideBarMenuItems;
