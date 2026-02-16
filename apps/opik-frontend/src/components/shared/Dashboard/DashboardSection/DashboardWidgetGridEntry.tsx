import usePluginsStore from "@/store/PluginsStore";
import DashboardWidgetGridContent, {
  DashboardWidgetGridProps,
} from "./DashboardWidgetGrid";

const DashboardWidgetGridEntry: React.FC<DashboardWidgetGridProps> = (
  props,
) => {
  const DashboardWidgetGrid = usePluginsStore(
    (state) => state.DashboardWidgetGrid,
  );

  if (DashboardWidgetGrid) {
    return <DashboardWidgetGrid {...props} />;
  }

  return <DashboardWidgetGridContent {...props} canViewExperiments />;
};

export default DashboardWidgetGridEntry;
