import React from "react";
import useUser from "./useUser";
import ExperimentCommentsViewerCore, {
  ExperimentCommentsViewerCoreProps,
} from "@/components/pages/CompareExperimentsPage/CompareExperimentsPanel/DataTab/ExperimentCommentsViewerCore";

const ExperimentCommentsViewer: React.FC<ExperimentCommentsViewerCoreProps> = (
  props,
) => {
  const { data: user } = useUser();

  if (!user) return;

  return <ExperimentCommentsViewerCore userName={user.userName} {...props} />;
};

export default ExperimentCommentsViewer;
