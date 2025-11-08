import React from "react";
import { useTheme } from "@/components/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import IntegrationCard from "@/components/pages-shared/onboarding/IntegrationExplorer/components/IntegrationCard";
import QuickInstallDialog from "@/components/pages-shared/onboarding/IntegrationExplorer/components/QuickInstallDialog";
import { useIntegrationExplorer } from "@/components/pages-shared/onboarding/IntegrationExplorer/IntegrationExplorerContext";
import cursorLogo from "/images/integrations/cursor.png";
import copilotLogo from "/images/integrations/copilot.png";
import copilotWhiteLogo from "/images/integrations/copilot-white.png";
import windsurfLogo from "/images/integrations/windsurf.png";
import windsurfWhiteLogo from "/images/integrations/windsurf-white.png";

const INTEGRATION_ICON_THEME_MAP = {
  cursor: {
    [THEME_MODE.LIGHT]: cursorLogo,
    [THEME_MODE.DARK]: cursorLogo,
  },
  copilot: {
    [THEME_MODE.LIGHT]: copilotLogo,
    [THEME_MODE.DARK]: copilotWhiteLogo,
  },
  windsurf: {
    [THEME_MODE.LIGHT]: windsurfLogo,
    [THEME_MODE.DARK]: windsurfWhiteLogo,
  },
} as const;

const IntegrationQuickInstall: React.FC = () => {
  const { selectedIntegrationId, setSelectedIntegrationId, source } =
    useIntegrationExplorer();
  const { themeMode } = useTheme();

  const handleQuickInstallClick = () => {
    setSelectedIntegrationId("quick-opik-install");
  };

  return (
    <>
      <IntegrationCard
        title="Quick install with AI assistants"
        description="Set up Opik fast with Cursor, Copilot, Windsurf, or your favorite AI assistant."
        size="lg"
        icon={
          <div className="flex items-center gap-1 pr-2">
            <img
              alt="Cursor"
              src={INTEGRATION_ICON_THEME_MAP.cursor[themeMode]}
              className="hidden size-[32px] shrink-0 md:block"
            />
            <img
              alt="Copilot"
              src={INTEGRATION_ICON_THEME_MAP.copilot[themeMode]}
              className="size-[36px] shrink-0 md:size-[22px]"
            />
            <img
              alt="Windsurf"
              src={INTEGRATION_ICON_THEME_MAP.windsurf[themeMode]}
              className="hidden size-[32px] shrink-0 md:block"
            />
          </div>
        }
        onClick={handleQuickInstallClick}
        id={`integration-quick-install-card${source ? `-${source}` : ""}`}
        data-fs-element={`IntegrationQuickInstallCard${
          source ? `-${source}` : ""
        }`}
      />

      <QuickInstallDialog
        open={selectedIntegrationId === "quick-opik-install"}
        onClose={() => setSelectedIntegrationId(undefined)}
      />
    </>
  );
};

export default IntegrationQuickInstall;
