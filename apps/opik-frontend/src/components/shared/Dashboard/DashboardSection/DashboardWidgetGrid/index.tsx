import usePluginsStore from "@/store/PluginsStore";
import DashboardWidgetGridContent, {
  DashboardWidgetGridProps,
} from "./DashboardWidgetGrid";

const DashboardWidgetGrid: React.FC<DashboardWidgetGridProps> = (props) => {
  const DashboardWidgetGridComponent = usePluginsStore(
    (state) => state.DashboardWidgetGrid,
  );

  if (DashboardWidgetGridComponent) {
    return <DashboardWidgetGridComponent {...props} />;
  }

  return <DashboardWidgetGridContent {...props} canViewExperiments />;
};

export default DashboardWidgetGrid;
