import React from "react";
import useUser from "./useUser";
import EvaluationExamplesCore from "@/components/pages-shared/onboarding/EvaluationExamples/EvaluationExamplesCore";

const EvaluationExamples: React.FC = () => {
  const { data: user } = useUser();

  if (!user) return;

  return <EvaluationExamplesCore apiKey={user.apiKeys[0]} />;
};

export default EvaluationExamples;
