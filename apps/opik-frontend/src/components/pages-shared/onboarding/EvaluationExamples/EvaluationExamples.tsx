import React from "react";
import usePluginsStore from "@/store/PluginsStore";
import EvaluationExamplesCore from "./EvaluationExamplesCore";

const EvaluationExamples = () => {
  const EvaluationExamples = usePluginsStore(
    (state) => state.EvaluationExamples,
  );

  if (EvaluationExamples) {
    return <EvaluationExamples />;
  }

  return <EvaluationExamplesCore />;
};

export default EvaluationExamples;
