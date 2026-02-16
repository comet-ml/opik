import useUserPermission from "@/plugins/comet/useUserPermission";
import WidgetConfigDialogAddStepContent, {
  WidgetConfigDialogAddStepProps,
} from "@/components/shared/Dashboard/WidgetConfigDialog/WidgetConfigDialogAddStep/WidgetConfigDialogAddStep";

const WidgetConfigDialogAddStep: React.FC<WidgetConfigDialogAddStepProps> = (
  props,
) => {
  const { canViewExperiments } = useUserPermission();

  return (
    <WidgetConfigDialogAddStepContent
      {...props}
      canViewExperiments={canViewExperiments}
    />
  );
};

export default WidgetConfigDialogAddStep;
