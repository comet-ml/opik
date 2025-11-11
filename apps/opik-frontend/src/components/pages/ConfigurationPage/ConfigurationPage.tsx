import React, { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { Outlet, useMatchRoute } from "@tanstack/react-router";
import AIProvidersTab from "@/components/pages/ConfigurationPage/AIProvidersTab/AIProvidersTab";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { StringParam, useQueryParam } from "use-query-params";
import FeedbackDefinitionsTab from "@/components/pages/ConfigurationPage/FeedbackDefinitionsTab/FeedbackDefinitionsTab";
import AlertsTab from "@/components/pages/ConfigurationPage/AlertsTab/AlertsTab";
import WorkspacePreferencesTab from "./WorkspacePreferencesTab/WorkspacePreferencesTab";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";

enum CONFIGURATION_TABS {
  FEEDBACK_DEFINITIONS = "feedback-definitions",
  AI_PROVIDER = "ai-provider",
  ALERTS = "alerts",
  WORKSPACE_PREFERENCES = "workspace-preferences",
}

const DEFAULT_TAB = CONFIGURATION_TABS.FEEDBACK_DEFINITIONS;

const ConfigurationPage = () => {
  const { t } = useTranslation();
  const [tab, setTab] = useQueryParam("tab", StringParam);
  const matchRoute = useMatchRoute();

  const isAlertsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.TOGGLE_ALERTS_ENABLED,
  );

  const isNestedAlertsRoute = matchRoute({
    to: "/$workspaceName/configuration/alerts",
    fuzzy: true,
  });

  useEffect(() => {
    if (!tab && !isNestedAlertsRoute) {
      setTab(DEFAULT_TAB, "replaceIn");
    }
  }, [tab, setTab, isNestedAlertsRoute]);

  if (isNestedAlertsRoute) {
    return <Outlet />;
  }

  return (
    <div className="pt-6">
      <h1 className="comet-title-l">{t("configuration.title")}</h1>

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
              {t("configuration.tabs.feedbackDefinitions")}
            </TabsTrigger>
            <TabsTrigger
              variant="underline"
              value={CONFIGURATION_TABS.AI_PROVIDER}
            >
              {t("configuration.tabs.aiProviders")}
            </TabsTrigger>
            {isAlertsEnabled && (
              <TabsTrigger
                variant="underline"
                value={CONFIGURATION_TABS.ALERTS}
              >
                {t("configuration.tabs.alerts")}
              </TabsTrigger>
            )}
            <TabsTrigger
              variant="underline"
              value={CONFIGURATION_TABS.WORKSPACE_PREFERENCES}
            >
              {t("configuration.tabs.workspacePreferences")}
            </TabsTrigger>
          </TabsList>

          <TabsContent value={CONFIGURATION_TABS.FEEDBACK_DEFINITIONS}>
            <FeedbackDefinitionsTab />
          </TabsContent>

          <TabsContent value={CONFIGURATION_TABS.AI_PROVIDER}>
            <AIProvidersTab />
          </TabsContent>

          {isAlertsEnabled && (
            <TabsContent value={CONFIGURATION_TABS.ALERTS}>
              <AlertsTab />
            </TabsContent>
          )}

          <TabsContent value={CONFIGURATION_TABS.WORKSPACE_PREFERENCES}>
            <WorkspacePreferencesTab />
          </TabsContent>
        </Tabs>
      </div>
    </div>
  );
};

export default ConfigurationPage;
