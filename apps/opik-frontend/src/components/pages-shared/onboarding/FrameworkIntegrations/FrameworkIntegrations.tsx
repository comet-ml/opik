import React, { useState } from "react";
import { Button } from "@/components/ui/button";
import { buildDocsUrl } from "@/lib/utils";
import { SquareArrowOutUpRight } from "lucide-react";
import { useTheme } from "@/components/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import ApiKeyCard from "../ApiKeyCard/ApiKeyCard";
import GoogleColabCard from "../GoogleColabCard/GoogleColabCard";
import IntegrationTemplate from "./IntegrationTemplate";
import {
  FrameworkIntegration,
  QUICKSTART_INTEGRATIONS,
} from "./quickstart-integrations";
import IntegrationListLayout from "../IntegrationListLayout/IntegrationListLayout";
import IntegrationTabs from "../IntegrationTabs/IntegrationTabs";
import { useUserApiKey } from "@/store/AppStore";
import { useIsPhone } from "@/hooks/useIsPhone";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

export type FrameworkIntegrationsProps = {
  integrationList?: FrameworkIntegration[];
  onRunCodeCallback?: () => void;
};
const FrameworkIntegrations: React.FC<FrameworkIntegrationsProps> = ({
  integrationList = QUICKSTART_INTEGRATIONS,
  onRunCodeCallback,
}) => {
  const [integrationIndex, setIntegrationIndex] = useState<number>(0);
  const integration = integrationList[integrationIndex];
  const apiKey = useUserApiKey();
  const { themeMode } = useTheme();
  const { isPhonePortrait } = useIsPhone();

  const handleFrameworkSelect = (value: string) => {
    const index = integrationList.findIndex((item) => item.label === value);
    if (index !== -1) {
      setIntegrationIndex(index);
    }
  };

  const getFrameworkLogo = (item: FrameworkIntegration, size: string) => (
    <img
      alt={item.label}
      src={
        themeMode === THEME_MODE.DARK && item.logoWhite
          ? item.logoWhite
          : item.logo
      }
      className={`${size} shrink-0`}
    />
  );

  const renderMobileFrameworkSelector = () => (
    <div className="flex flex-col gap-1">
      <label className="comet-body-s-accented px-0.5 pb-0.5">
        Select framework
      </label>
      <Select value={integration.label} onValueChange={handleFrameworkSelect}>
        <SelectTrigger className="w-full">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {integrationList.map((item) => (
            <SelectItem key={item.label} value={item.label}>
              <div className="flex items-center gap-2">
                {getFrameworkLogo(item, "size-4")}
                <span>{item.label}</span>
              </div>
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );

  const renderDesktopFrameworkSelector = () => (
    <>
      <IntegrationTabs.Title>Select framework</IntegrationTabs.Title>
      <IntegrationTabs>
        {integrationList.map((item, index) => (
          <IntegrationTabs.Item
            key={item.label}
            onClick={() => setIntegrationIndex(index)}
            isActive={index === integrationIndex}
          >
            {getFrameworkLogo(item, "size-[32px]")}
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
  );

  return (
    <IntegrationListLayout
      leftSidebar={
        isPhonePortrait
          ? renderMobileFrameworkSelector()
          : renderDesktopFrameworkSelector()
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
        onRunCodeCallback={onRunCodeCallback}
      />
    </IntegrationListLayout>
  );
};

export default FrameworkIntegrations;
