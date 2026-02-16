import useUserPermission from "@/plugins/comet/useUserPermission";
import DashboardWidgetGridContent, {
  DashboardWidgetGridProps,
} from "@/components/shared/Dashboard/DashboardSection/DashboardWidgetGrid/DashboardWidgetGrid";

const DashboardWidgetGrid: React.FC<DashboardWidgetGridProps> = (props) => {
  const { canViewExperiments } = useUserPermission();

  return (
    <DashboardWidgetGridContent
      {...props}
      canViewExperiments={canViewExperiments}
    />
  );
};

export default DashboardWidgetGrid;
