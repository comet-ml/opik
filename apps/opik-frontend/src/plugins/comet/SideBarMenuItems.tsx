import SideBarMenuItems from "@/components/layout/SideBar/SideBarMenuItems";
import { withExperimentsViewPermission } from "./withExperimentsViewPermission";
import { withDashboardsViewPermission } from "./withDashboardsViewPermission";

export default withExperimentsViewPermission(
  withDashboardsViewPermission(SideBarMenuItems),
);
