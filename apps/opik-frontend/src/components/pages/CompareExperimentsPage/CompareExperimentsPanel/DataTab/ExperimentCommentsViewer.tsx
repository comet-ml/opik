import usePluginsStore from "@/store/PluginsStore";
import ExperimentCommentsViewerCore, {
  ExperimentCommentsViewerCoreProps,
} from "./ExperimentCommentsViewerCore";

export type ExperimentCommentsViewerProps = Omit<
  ExperimentCommentsViewerCoreProps,
  "user"
>;
const ExperimentCommentsViewer: React.FC<ExperimentCommentsViewerProps> = (
  props,
) => {
  const ExperimentCommentsViewer = usePluginsStore(
    (state) => state.ExperimentCommentsViewer,
  );

  if (ExperimentCommentsViewer) {
    return <ExperimentCommentsViewer {...props} />;
  }

  return <ExperimentCommentsViewerCore {...props} />;
};

export default ExperimentCommentsViewer;
