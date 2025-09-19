import React from "react";
import useLocalStorageState from "use-local-storage-state";

import WorkspaceStatisticSection from "@/components/pages/HomePage/WorkspaceStatisticSection";
import OverallPerformanceSection from "@/components/pages/HomePage/OverallPerformanceSection";
import ObservabilitySection from "@/components/pages/HomePage/ObservabilitySection";
import EvaluationSection from "@/components/pages/HomePage/EvaluationSection";
import WelcomeBanner from "@/components/pages/HomePage/WecomeBanner";
import { PostHogFeature } from "posthog-js/react";

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
          <PostHogFeature flag="home-page-title">
            <h1 className="comet-title-l truncate break-words">
              Welcome back to Opik
            </h1>
          </PostHogFeature>
          <PostHogFeature flag="home-page-title" match={"home-page-title-old"}>
            <h1 className="comet-title-l truncate break-words">
              Old Welcome back to Opik
            </h1>
          </PostHogFeature>
          <PostHogFeature flag="home-page-title" match={"home-page-title-new"}>
            <h1 className="comet-title-l truncate break-words">
              New Welcome back to Opik
            </h1>
          </PostHogFeature>
          <PostHogFeature flag="home-page-title" match={"control"}>
            <h1 className="comet-title-l truncate break-words">
              Control Welcome back to Opik
            </h1>
          </PostHogFeature>
        </div>
      )}
      <WorkspaceStatisticSection />
      <OverallPerformanceSection />
      <ObservabilitySection />
      <EvaluationSection />
    </div>
  );
};

export default HomePage;
