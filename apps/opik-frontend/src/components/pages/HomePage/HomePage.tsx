import React from "react";
import useLocalStorageState from "use-local-storage-state";

import WorkspaceStatisticSection from "@/components/pages/HomePage/WorkspaceStatisticSection";
import OverallPerformanceSection from "@/components/pages/HomePage/OverallPerformanceSection";
import ObservabilitySection from "@/components/pages/HomePage/ObservabilitySection";
import EvaluationSection from "@/components/pages/HomePage/EvaluationSection";
import OptimizationRunsSection from "@/components/pages/HomePage/OptimizationRunsSection";
import WelcomeBanner from "@/components/pages/HomePage/WecomeBanner";

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
