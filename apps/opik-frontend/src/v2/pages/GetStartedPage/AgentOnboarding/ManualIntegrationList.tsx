import React, { useMemo } from "react";
import { ExternalLink } from "lucide-react";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import IntegrationCard from "@/v2/pages-shared/onboarding/IntegrationExplorer/components/IntegrationCard";
import {
  INTEGRATION_CATEGORIES,
  getIntegrationsByCategory,
} from "@/constants/integrations";
import { buildDocsUrl } from "@/lib/utils";
import InstallWithAITab from "@/v2/pages-shared/onboarding/InstallWithAITab";
import { useAgentOnboarding } from "./AgentOnboardingContext";

const INSTALL_WITH_AI = "install-with-ai";

type ManualIntegrationListProps = {
  onSelectIntegration: (id: string) => void;
  showInstallWithAI?: boolean;
  traceReceived?: boolean;
  activeCategory?: string | null;
  onCategoryChange?: (category: string) => void;
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
  showInstallWithAI = false,
  traceReceived = false,
  activeCategory: controlledCategory,
  onCategoryChange,
}) => {
  const { agentName } = useAgentOnboarding();
  const { themeMode } = useTheme();

  const defaultCategory = showInstallWithAI
    ? INSTALL_WITH_AI
    : INTEGRATION_CATEGORIES.ALL;
  const activeCategory = controlledCategory ?? defaultCategory;
  const setActiveCategory = (value: string) => {
    onCategoryChange?.(value);
  };

  const integrations = useMemo(
    () => getIntegrationsByCategory(activeCategory),
    [activeCategory],
  );

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-4">
        <ToggleGroup
          type="single"
          value={activeCategory}
          onValueChange={(v) => v && setActiveCategory(v)}
          variant="secondary"
          size="sm"
          className="justify-start"
        >
          {showInstallWithAI && (
            <ToggleGroupItem
              value={INSTALL_WITH_AI}
              className="text-muted-slate"
            >
              Use Opik skills
            </ToggleGroupItem>
          )}
          {CATEGORY_TABS.map((tab) => (
            <ToggleGroupItem
              key={tab.value}
              value={tab.value}
              className="text-muted-slate"
            >
              {tab.label}
            </ToggleGroupItem>
          ))}
        </ToggleGroup>

        {activeCategory === INSTALL_WITH_AI ? (
          <InstallWithAITab
            traceReceived={traceReceived}
            agentName={agentName}
          />
        ) : (
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
                  className="h-8 gap-1 overflow-hidden p-[0.375rem_0.5rem] [&>div:last-child]:min-w-0 [&_h3]:truncate [&_h3]:font-normal"
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
              className="flex h-8 items-center gap-1 rounded-lg border bg-background px-3 py-1.5 transition-all duration-200 hover:bg-primary-foreground"
              id="onboarding-integration-view-all"
              data-fs-element="OnboardingIntegrationViewAll"
            >
              <span className="comet-body-s-accented truncate font-normal text-foreground">
                View all
              </span>
              <ExternalLink className="size-3 shrink-0 text-foreground" />
            </a>
          </div>
        )}
      </div>
    </div>
  );
};

export default ManualIntegrationList;
