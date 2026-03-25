import React, { createContext, useContext, useState } from "react";
import { BooleanParam, StringParam, useQueryParam } from "use-query-params";
import { INTEGRATION_CATEGORIES } from "@/constants/integrations";

export type IntegrationExplorerContextType = {
  searchText: string;
  activeTab: string;
  selectedIntegrationId?: string | null;
  setSelectedIntegrationId: (id?: string) => void;
  helpGuideDialogOpen?: boolean | null;
  setHelpGuideDialogOpen: (open?: boolean) => void;
  setSearchText: (text: string) => void;
  setActiveTab: (tab: string) => void;
  source?: string;
};

export type IntegrationExplorerProviderProps = {
  children: React.ReactNode;
  source?: string;
};

const IntegrationExplorerContext =
  createContext<IntegrationExplorerContextType | null>(null);

export const useIntegrationExplorer = () => {
  const context = useContext(IntegrationExplorerContext);
  if (!context) {
    throw new Error(
      "useIntegrationExplorer must be used within IntegrationExplorerProvider",
    );
  }
  return context;
};

export const IntegrationExplorerProvider: React.FunctionComponent<
  IntegrationExplorerProviderProps
> = ({ children, source }) => {
  const [searchText, setSearchText] = useState("");
  const [activeTab, setActiveTab] = useState<string>(
    INTEGRATION_CATEGORIES.ALL,
  );

  const [selectedIntegrationId, setSelectedIntegrationId] = useQueryParam(
    "integration",
    StringParam,
    {
      updateType: "replaceIn",
    },
  );

  const [helpGuideDialogOpen, setHelpGuideDialogOpen] = useQueryParam(
    "help",
    BooleanParam,
    {
      updateType: "replaceIn",
    },
  );

  const contextValue: IntegrationExplorerContextType = {
    searchText,
    activeTab,
    selectedIntegrationId,
    setSelectedIntegrationId,
    helpGuideDialogOpen,
    setHelpGuideDialogOpen,
    setSearchText,
    setActiveTab,
    source,
  };

  return (
    <IntegrationExplorerContext.Provider value={contextValue}>
      {children}
    </IntegrationExplorerContext.Provider>
  );
};
