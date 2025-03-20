import React from "react";
import usePluginsStore from "@/store/PluginsStore";
import CreateExperimentCodeCore, {
  CreateExperimentCodeCoreProps,
} from "./CreateExperimentCodeCore";

export type CreateExperimentCodeProps = Omit<
  CreateExperimentCodeCoreProps,
  "apiKey"
>;
const CreateExperimentCode: React.FC<CreateExperimentCodeProps> = (props) => {
  const CreateExperimentCode = usePluginsStore(
    (state) => state.CreateExperimentCode,
  );

  if (CreateExperimentCode) {
    return <CreateExperimentCode {...props} />;
  }

  return <CreateExperimentCodeCore {...props} />;
};

export default CreateExperimentCode;
