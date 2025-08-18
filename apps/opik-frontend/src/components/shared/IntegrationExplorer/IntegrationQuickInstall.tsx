import React from "react";
import IntegrationCard from "@/components/shared/IntegrationExplorer/components/IntegrationCard";
import QuickInstallDialog from "@/components/shared/IntegrationExplorer/components/QuickInstallDialog";
import { useIntegrationExplorer } from "@/components/shared/IntegrationExplorer/IntegrationExplorerContext";
import cursorLogo from "/images/integrations/cursor.png";
import copilotLogo from "/images/integrations/copilot.png";
import windsurfLogo from "/images/integrations/windsurf.png";

const IntegrationQuickInstall: React.FC = () => {
  const { selectedIntegrationId, setSelectedIntegrationId } =
    useIntegrationExplorer();

  const handleQuickInstallClick = () => {
    setSelectedIntegrationId("quick-opik-install");
  };

  return (
    <>
      <IntegrationCard
        title="Quick install with AI assistants"
        description="Set up Opik fast with Cursor, Copilot, Windsurf, or your favorite AI assistant."
        icon={
          <div className="flex gap-1 pr-2">
            <img
              alt="Cursor"
              src={cursorLogo}
              className="size-[32px] shrink-0"
            />
            <img
              alt="Copilot"
              src={copilotLogo}
              className="size-[32px] shrink-0"
            />
            <img
              alt="Windsurf"
              src={windsurfLogo}
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
