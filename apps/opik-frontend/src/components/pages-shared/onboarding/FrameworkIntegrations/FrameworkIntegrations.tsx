import React, { useState } from "react";
import { FrameworkIntegration } from "./types";
import { Button } from "@/components/ui/button";
import { buildDocsUrl } from "@/lib/utils";
import { SquareArrowOutUpRight } from "lucide-react";
import ApiKeyCard from "../ApiKeyCard/ApiKeyCard";
import GoogleColabCard from "../GoogleColabCard/GoogleColabCard";
import IntegrationTemplate from "./IntegrationTemplate";

type FrameworkIntegrationsProps = {
  integrationList: FrameworkIntegration[];
  apiKey?: string;
  showColabLinks?: boolean;
};
const FrameworkIntegrations: React.FC<FrameworkIntegrationsProps> = ({
  integrationList,
  apiKey,
  showColabLinks,
}) => {
  const [integrationIndex, setIntegrationIndex] = useState<number>(0);
  const integration = integrationList[integrationIndex];

  return (
    <div className="m-auto flex w-full max-w-[1250px] gap-6">
      <div className="sticky top-20 flex w-[250px] shrink-0 flex-col gap-4 self-start">
        <h4 className="comet-title-s">Select framework</h4>
        <ul className="flex flex-col gap-2">
          {integrationList.map((item, index) => (
            <li
              key={item.label}
              className="comet-body-s flex h-10 w-full cursor-pointer items-center gap-2 rounded-md pl-2 pr-4 text-foreground hover:bg-primary-foreground data-[status=active]:bg-primary-100"
              onClick={() => setIntegrationIndex(index)}
              data-status={index === integrationIndex ? "active" : "inactive"}
            >
              <img
                alt={item.label}
                src={item.logo}
                className="size-[32px] shrink-0"
              />
              <div className="ml-1 truncate">{item.label}</div>
            </li>
          ))}
        </ul>
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
      </div>
      <div className="flex min-w-[650px] flex-1 gap-6">
        <div className="flex w-full flex-1 flex-col">
          <IntegrationTemplate code={integration.code} apiKey={apiKey} />
        </div>

        <div className="sticky top-20 flex w-[250px] shrink-0 flex-col gap-6 self-start">
          {apiKey && <ApiKeyCard apiKey={apiKey} />}
          {showColabLinks ? <GoogleColabCard link={integration.colab} /> : null}
        </div>
      </div>
    </div>
  );
};

export default FrameworkIntegrations;
