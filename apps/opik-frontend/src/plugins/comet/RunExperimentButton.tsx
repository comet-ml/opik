import React from "react";
import RunExperimentButtonComponent, {
  RunExperimentButtonProps,
} from "@/components/pages/HomePage/GetStartedSection/RunExperimentButton";
import useUserPermission from "./useUserPermission";

const RunExperimentButton: React.FC<RunExperimentButtonProps> = (props) => {
  const { canViewExperiments } = useUserPermission();

  return (
    <RunExperimentButtonComponent
      {...props}
      canViewExperiments={!!canViewExperiments}
    />
  );
};

export default RunExperimentButton;
