import usePluginsStore from "@/store/PluginsStore";
import WidgetConfigDialogAddStepContent, {
  WidgetConfigDialogAddStepProps,
} from "./WidgetConfigDialogAddStep";

const WidgetConfigDialogAddStepEntry: React.FC<
  WidgetConfigDialogAddStepProps
> = (props) => {
  const WidgetConfigDialogAddStep = usePluginsStore(
    (state) => state.WidgetConfigDialogAddStep,
  );

  if (WidgetConfigDialogAddStep) {
    return <WidgetConfigDialogAddStep {...props} />;
  }

  return <WidgetConfigDialogAddStepContent {...props} canViewExperiments />;
};

export default WidgetConfigDialogAddStepEntry;
