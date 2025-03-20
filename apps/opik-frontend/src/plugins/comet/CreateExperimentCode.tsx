import React from "react";
import useUser from "./useUser";
import CreateExperimentCodeCore, {
  CreateExperimentCodeCoreProps,
} from "@/components/pages-shared/onboarding/CreateExperimentCode/CreateExperimentCodeCore";

const CreateExperimentCode: React.FC<CreateExperimentCodeCoreProps> = (
  props,
) => {
  const { data: user } = useUser();

  if (!user) return;

  return <CreateExperimentCodeCore {...props} apiKey={user.apiKeys[0]} />;
};

export default CreateExperimentCode;
