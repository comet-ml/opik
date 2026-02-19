import React from "react";
import SideBarMenuItemsContent, {
  SideBarMenuItemsProps,
} from "@/components/layout/SideBar/SideBarMenuItems";
import useUserPermission from "./useUserPermission";

const SideBarMenuItems: React.FC<SideBarMenuItemsProps> = (props) => {
  const { canViewExperiments } = useUserPermission();

  return (
    <SideBarMenuItemsContent
      {...props}
      canViewExperiments={canViewExperiments}
    />
  );
};

export default SideBarMenuItems;
