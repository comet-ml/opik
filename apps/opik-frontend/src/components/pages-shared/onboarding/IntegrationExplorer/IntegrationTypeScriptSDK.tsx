import React from "react";
import IntegrationCard from "@/components/pages-shared/onboarding/IntegrationExplorer/components/IntegrationCard";
import tsLogo from "@/icons/ts-logo.svg";
import { buildDocsUrl } from "@/lib/utils";
import { useIntegrationExplorer } from "@/components/pages-shared/onboarding/IntegrationExplorer/IntegrationExplorerContext";

const IntegrationTypeScriptSDK: React.FC = () => {
  const { source } = useIntegrationExplorer();

  return (
    <a
      href={buildDocsUrl(
        "/reference/typescript-sdk/overview",
        "&utm_source=opik_frontend&utm_medium=integration_explorer&utm_campaign=typescript_sdk&utm_content=integration_card",
      )}
      target="_blank"
      rel="noopener noreferrer"
    >
      <IntegrationCard
        title="TypeScript SDK"
        description="Bring observability and evaluations to your JS apps"
        size="lg"
        icon={
          <img alt="TypeScript" src={tsLogo} className="size-[32px] shrink-0" />
        }
        tag="New"
        id={`integration-typescript-sdk-card${source ? `-${source}` : ""}`}
        data-fs-element={`IntegrationTypeScriptSDKCard${
          source ? `-${source}` : ""
        }`}
      />
    </a>
  );
};

export default IntegrationTypeScriptSDK;
