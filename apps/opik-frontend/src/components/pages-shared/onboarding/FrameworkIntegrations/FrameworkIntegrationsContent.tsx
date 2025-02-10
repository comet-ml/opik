import React, { useState } from "react";
import { Button } from "@/components/ui/button";
import { buildDocsUrl } from "@/lib/utils";
import { SquareArrowOutUpRight } from "lucide-react";
import ApiKeyCard from "../ApiKeyCard/ApiKeyCard";
import GoogleColabCard from "../GoogleColabCard/GoogleColabCard";
import IntegrationTemplate from "./IntegrationTemplate";
import {
  FrameworkIntegration,
  QUICKSTART_INTEGRATIONS,
} from "./quickstart-integrations";
import IntegrationListLayout from "../IntegrationListLayout/IntegrationListLayout";
import IntegrationTabs from "../IntegrationTabs/IntegrationTabs";

export type FrameworkIntegrationsContentProps = {
  integrationList?: FrameworkIntegration[];
  apiKey?: string;
};
const FrameworkIntegrationsContent: React.FC<
  FrameworkIntegrationsContentProps
> = ({ integrationList = QUICKSTART_INTEGRATIONS, apiKey }) => {
  const [integrationIndex, setIntegrationIndex] = useState<number>(0);
  const integration = integrationList[integrationIndex];

  return (
    <IntegrationListLayout
      leftSidebar={
        <>
          <IntegrationTabs.Title>Select framework</IntegrationTabs.Title>
          <IntegrationTabs>
            {integrationList.map((item, index) => (
              <IntegrationTabs.Item
                key={item.label}
                onClick={() => setIntegrationIndex(index)}
                isActive={index === integrationIndex}
              >
                <img
                  alt={item.label}
                  src={item.logo}
                  className="size-[32px] shrink-0"
                />
                <div className="ml-1 truncate">{item.label}</div>
              </IntegrationTabs.Item>
            ))}
          </IntegrationTabs>
          <Button className="w-fit pl-2" variant="ghost" asChild>
            <a
              href={buildDocsUrl("/tracing/integrations/overview")}
              target="_blank"
              rel="noreferrer"
            >
              Explore all integrations
              <SquareArrowOutUpRight className="ml-2 size-4 shrink-0" />
            </a>
          </Button>
        </>
      }
      rightSidebar={
        <>
          <ApiKeyCard />
          <GoogleColabCard link={integration.colab} />
        </>
      }
    >
      <IntegrationTemplate
        code={integration.code}
        apiKey={apiKey}
        executionUrl={integration.executionUrl}
        executionLogs={integration.executionLogs}
        withLineHighlights
      />
    </IntegrationListLayout>
  );
};

export default FrameworkIntegrationsContent;
