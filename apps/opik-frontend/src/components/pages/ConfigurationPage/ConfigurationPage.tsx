import React, { useEffect } from "react";
import AIProvidersTab from "@/components/pages/ConfigurationPage/AIProvidersTab/AIProvidersTab";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { StringParam, useQueryParam } from "use-query-params";
import FeedbackDefinitionsTab from "@/components/pages/ConfigurationPage/FeedbackDefinitionsTab/FeedbackDefinitionsTab";

enum CONFIGURATION_TABS {
  FEEDBACK_DEFINITIONS = "feedback-definitions",
  AI_PROVIDER = "ai-provider",
}

const DEFAULT_TAB = CONFIGURATION_TABS.FEEDBACK_DEFINITIONS;

const ConfigurationPage = () => {
  const [tab, setTab] = useQueryParam("tab", StringParam);

  useEffect(() => {
    if (!tab) {
      setTab(DEFAULT_TAB, "replaceIn");
    }
  }, [tab, setTab]);

  return (
    <div className="pt-6">
      <h1 className="comet-title-l">Configuration</h1>

      <div className="mt-6">
        <Tabs
          defaultValue="feedback-definitions"
          value={tab as string}
          onValueChange={setTab}
        >
          <TabsList variant="underline">
            <TabsTrigger
              variant="underline"
              value={CONFIGURATION_TABS.FEEDBACK_DEFINITIONS}
            >
              Feedback definitions
            </TabsTrigger>
            <TabsTrigger
              variant="underline"
              value={CONFIGURATION_TABS.AI_PROVIDER}
            >
              AI Providers
            </TabsTrigger>
          </TabsList>

          <TabsContent value={CONFIGURATION_TABS.FEEDBACK_DEFINITIONS}>
            <FeedbackDefinitionsTab />
          </TabsContent>

          <TabsContent value={CONFIGURATION_TABS.AI_PROVIDER}>
            <AIProvidersTab />
          </TabsContent>
        </Tabs>
      </div>
    </div>
  );
};

export default ConfigurationPage;
