import React from "react";
import { useTheme } from "@/components/theme-provider";
import IntegrationCard from "@/components/pages-shared/onboarding/IntegrationExplorer/components/IntegrationCard";
import QuickInstallDialog from "@/components/pages-shared/onboarding/IntegrationExplorer/components/QuickInstallDialog";
import { useIntegrationExplorer } from "@/components/pages-shared/onboarding/IntegrationExplorer/IntegrationExplorerContext";
import cursorLogo from "/images/integrations/cursor.png";
import copilotLogo from "/images/integrations/copilot.png";
import copilotWhiteLogo from "/images/integrations/copilot-white.png";
import windsurfLogo from "/images/integrations/windsurf.png";
import windsurfWhiteLogo from "/images/integrations/windsurf-white.png";

const IntegrationQuickInstall: React.FC = () => {
  const { selectedIntegrationId, setSelectedIntegrationId } =
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
        icon={
          <div className="flex items-center gap-1 pr-2">
            <img
              alt="Cursor"
              src={cursorLogo}
              className="size-[32px] shrink-0"
            />
            <img
              alt="Copilot"
              src={themeMode === "dark" ? copilotWhiteLogo : copilotLogo}
              className="size-[20px] shrink-0"
            />
            <img
              alt="Windsurf"
              src={themeMode === "dark" ? windsurfWhiteLogo : windsurfLogo}
              className="size-[32px] shrink-0"
            />
          </div>
        }
        tag="New"
        className="mb-4"
        onClick={handleQuickInstallClick}
      />

      <QuickInstallDialog
        open={selectedIntegrationId === "quick-opik-install"}
        onClose={() => setSelectedIntegrationId(undefined)}
      />
    </>
  );
};

export default IntegrationQuickInstall;
