import React from "react";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import { INTEGRATION_CATEGORIES } from "@/constants/integrations";
import { useIntegrationExplorer } from "./IntegrationExplorerContext";

type IntegrationTabsProps = {
  children: React.ReactNode;
  className?: string;
};

const IntegrationTabs: React.FunctionComponent<IntegrationTabsProps> = ({
  children,
  className = "w-full",
}) => {
  const { activeTab, setActiveTab } = useIntegrationExplorer();

  return (
    <Tabs value={activeTab} onValueChange={setActiveTab} className={className}>
      <TabsList variant="underline" className="w-full justify-start">
        <TabsTrigger value={INTEGRATION_CATEGORIES.ALL} variant="underline">
          {INTEGRATION_CATEGORIES.ALL}
        </TabsTrigger>
        <TabsTrigger
          value={INTEGRATION_CATEGORIES.LLM_PROVIDERS}
          variant="underline"
        >
          {INTEGRATION_CATEGORIES.LLM_PROVIDERS}
        </TabsTrigger>
        <TabsTrigger
          value={INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS}
          variant="underline"
        >
          {INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS}
        </TabsTrigger>
      </TabsList>

      <TabsContent value={activeTab} className="mt-6">
        {children}
      </TabsContent>
    </Tabs>
  );
};

export default IntegrationTabs;
