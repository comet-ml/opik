import usePluginsStore from "@/store/PluginsStore";
import WidgetConfigDialogAddStepContent, {
  WidgetConfigDialogAddStepProps,
} from "./WidgetConfigDialogAddStep";

const WidgetConfigDialogAddStep: React.FC<WidgetConfigDialogAddStepProps> = (
  props,
) => {
  const WidgetConfigDialogAddStepComponent = usePluginsStore(
    (state) => state.WidgetConfigDialogAddStep,
  );

  if (WidgetConfigDialogAddStepComponent) {
    return <WidgetConfigDialogAddStepComponent {...props} />;
  }

  return <WidgetConfigDialogAddStepContent {...props} canViewExperiments />;
};

export default WidgetConfigDialogAddStep;
