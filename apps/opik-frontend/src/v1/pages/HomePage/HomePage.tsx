import React from "react";
import useLocalStorageState from "use-local-storage-state";

import WorkspaceStatisticSection from "@/v1/pages/HomePage/WorkspaceStatisticSection";
import OverallPerformanceSection from "@/v1/pages/HomePage/OverallPerformanceSection";
import ObservabilitySection from "@/v1/pages/HomePage/ObservabilitySection";
import EvaluationSection from "@/v1/pages/HomePage/EvaluationSection";
import OptimizationRunsSection from "@/v1/pages/HomePage/OptimizationRunsSection";
import WelcomeBanner from "@/v1/pages/HomePage/WecomeBanner";

const SHOW_WELCOME_MESSAGE_KEY = "home-welcome-message";

const HomePage = () => {
  const [showWelcomeMessage, setShowWelcomeMessage] =
    useLocalStorageState<boolean>(SHOW_WELCOME_MESSAGE_KEY, {
      defaultValue: true,
    });

  return (
    <div className="pt-6">
      {showWelcomeMessage ? (
        <WelcomeBanner setOpen={setShowWelcomeMessage} />
      ) : (
        <div className="mb-4 flex items-center justify-between">
          <h1 className="comet-title-l truncate break-words">
            Welcome back to Opik
          </h1>
        </div>
      )}
      <WorkspaceStatisticSection />
      <OverallPerformanceSection />
      <ObservabilitySection />
      <EvaluationSection />
      <OptimizationRunsSection />
    </div>
  );
};

export default HomePage;
