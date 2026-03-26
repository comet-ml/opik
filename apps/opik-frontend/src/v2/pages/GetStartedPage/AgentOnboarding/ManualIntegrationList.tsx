import React, { useMemo, useState } from "react";
import { BookOpen } from "lucide-react";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/ui/tabs";
import IntegrationCard from "@/v2/pages-shared/onboarding/IntegrationExplorer/components/IntegrationCard";
import CopyButton from "@/shared/CopyButton/CopyButton";
import { useAgentOnboarding } from "./AgentOnboardingContext";
import { useUserApiKey } from "@/store/AppStore";
import {
  INTEGRATION_CATEGORIES,
  getIntegrationsByCategory,
} from "@/constants/integrations";
import { buildDocsUrl } from "@/lib/utils";

type ManualIntegrationListProps = {
  onSelectIntegration: (id: string) => void;
};

const CATEGORY_TABS = [
  { value: INTEGRATION_CATEGORIES.ALL, label: "All integrations" },
  { value: INTEGRATION_CATEGORIES.LLM_PROVIDERS, label: "LLM providers" },
  {
    value: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    label: "Frameworks & tools",
  },
] as const;

const ManualIntegrationList: React.FC<ManualIntegrationListProps> = ({
  onSelectIntegration,
}) => {
  const { agentName } = useAgentOnboarding();
  const apiKey = useUserApiKey();
  const { themeMode } = useTheme();
  const [activeCategory, setActiveCategory] = useState<string>(
    INTEGRATION_CATEGORIES.ALL,
  );

  const integrations = useMemo(
    () => getIntegrationsByCategory(activeCategory),
    [activeCategory],
  );

  return (
    <div className="flex flex-col gap-4">
      <div className="flex gap-3">
        <div className="flex flex-1 flex-col gap-1">
          <span className="comet-body-s-accented px-0.5 pb-0.5">
            Project name
          </span>
          <div className="flex items-center gap-1 rounded border bg-primary-foreground px-2 py-1">
            <code className="comet-body-xs text-muted-slate">{agentName}</code>
            <CopyButton
              text={agentName}
              message="Project name copied"
              tooltipText="Copy project name"
              size="icon-3xs"
              className="ml-auto"
            />
          </div>
        </div>
        <div className="flex flex-1 flex-col gap-1">
          <span className="comet-body-s-accented px-0.5 pb-0.5">API key</span>
          <div className="flex items-center gap-1 rounded border bg-primary-foreground px-2 py-1">
            <code className="comet-body-xs text-muted-slate">
              opk-***-your-api-key
            </code>
            <CopyButton
              text={apiKey || ""}
              message="API key copied"
              tooltipText="Copy API key"
              size="icon-3xs"
              className="ml-auto"
            />
          </div>
        </div>
      </div>

      <Tabs value={activeCategory} onValueChange={setActiveCategory}>
        <TabsList variant="underline">
          {CATEGORY_TABS.map((tab) => (
            <TabsTrigger key={tab.value} value={tab.value} variant="underline">
              {tab.label}
            </TabsTrigger>
          ))}
        </TabsList>

        <TabsContent value={activeCategory}>
          <div className="grid grid-cols-4 gap-2">
            {integrations.map((integration) => (
              <div key={integration.id} title={integration.title}>
                <IntegrationCard
                  title={integration.title}
                  icon={
                    <img
                      alt={integration.title}
                      src={
                        themeMode === THEME_MODE.DARK && integration.whiteIcon
                          ? integration.whiteIcon
                          : integration.icon
                      }
                      className="size-4 shrink-0"
                    />
                  }
                  className="h-8 gap-1 overflow-hidden p-[0.375rem_0.5rem] [&>div:last-child]:min-w-0 [&_h3]:truncate"
                  iconClassName="min-w-0"
                  onClick={() => onSelectIntegration(integration.id)}
                  id={`onboarding-integration-card-${integration.id}`}
                  data-fs-element={`OnboardingIntegrationCard-${integration.id}`}
                />
              </div>
            ))}

            <a
              href={buildDocsUrl(
                "/integrations/overview",
                "&utm_source=opik_frontend&utm_medium=onboarding&utm_campaign=integrations_docs",
              )}
              target="_blank"
              rel="noopener noreferrer"
            >
              <IntegrationCard
                title="View all"
                icon={
                  <div className="flex size-4 items-center justify-center rounded bg-primary/10">
                    <BookOpen className="size-3 text-primary" />
                  </div>
                }
                className="h-8 gap-1 overflow-hidden p-[0.375rem_0.5rem] [&>div:last-child]:min-w-0 [&_h3]:truncate"
                iconClassName="min-w-0"
                id="onboarding-integration-view-all"
                data-fs-element="OnboardingIntegrationViewAll"
              />
            </a>
          </div>
        </TabsContent>
      </Tabs>
    </div>
  );
};

export default ManualIntegrationList;
