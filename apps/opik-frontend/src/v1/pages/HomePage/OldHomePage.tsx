import React from "react";
import ObservabilitySection from "@/v1/pages/HomePage/ObservabilitySection";
import EvaluationSection from "@/v1/pages/HomePage/EvaluationSection";
import GetStartedSection from "@/v1/pages/HomePage/GetStartedSection";
import { calculateWorkspaceName } from "@/lib/utils";
import useAppStore from "@/store/AppStore";
import OptimizationRunsSection from "./OptimizationRunsSection";

const OldHomePage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <div className="pt-6">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">
          Welcome to {calculateWorkspaceName(workspaceName, "Opik")}
        </h1>
      </div>
      <GetStartedSection />
      <ObservabilitySection />
      <EvaluationSection />
      <OptimizationRunsSection />
    </div>
  );
};

export default OldHomePage;
