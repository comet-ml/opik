import SideBarMenuItems from "@/components/layout/SideBar/SideBarMenuItems";
import { withExperimentsViewPermission } from "./withExperimentsViewPermission";

// const SideBarMenuItems: React.FC<SideBarMenuItemsProps> = (props) => {
//   const { canViewExperiments, canViewDashboards } = useUserPermission();

//   return (
//     <SideBarMenuItemsContent
//       {...props}
//       canViewExperiments={canViewExperiments}
//       canViewDashboards={canViewDashboards}
//     />
//   );
// };

// export default SideBarMenuItems;

export default withExperimentsViewPermission(SideBarMenuItems);
