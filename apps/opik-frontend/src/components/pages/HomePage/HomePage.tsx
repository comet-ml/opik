import React from "react";
import useAppStore from "@/store/AppStore";
import GetStartedSection from "@/components/pages/HomePage/GetStartedSection";
import ObservabilitySection from "@/components/pages/HomePage/ObservabilitySection";
import EvaluationSection from "@/components/pages/HomePage/EvaluationSection";

const HomePage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <div className="pt-6">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">
          Welcome to {workspaceName}
        </h1>
      </div>
      <GetStartedSection />
      <ObservabilitySection />
      <div className="h-6"></div>
      <EvaluationSection />
    </div>
  );
};

export default HomePage;
