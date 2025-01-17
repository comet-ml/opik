import React from "react";
import useAppStore from "@/store/AppStore";
import GetStartedSection from "@/components/pages/HomePage/GetStartedSection";
import ObservabilitySection from "@/components/pages/HomePage/ObservabilitySection";
import EvaluationSection from "@/components/pages/HomePage/EvaluationSection";
import { calculateWorkspaceName } from "@/lib/utils";

const HomePage = () => {
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
    </div>
  );
};

export default HomePage;
