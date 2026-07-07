import React, { createContext, useContext, ReactNode } from "react";

export type AiSpendContextValue = {
  isPending: boolean;
  hasAccess: boolean;
  isOrganizationAdmin?: boolean;
  spendWorkspaceName?: string;
  isSpendWorkspaceActive?: boolean;
  organizationName?: string;
  goToCostIntelligence: () => void;
};

const DEFAULT_VALUE: AiSpendContextValue = {
  isPending: false,
  hasAccess: false,
  isOrganizationAdmin: false,
  goToCostIntelligence: () => {},
};

const AiSpendContext = createContext<AiSpendContextValue>(DEFAULT_VALUE);

export const AiSpendProvider: React.FC<{
  children: ReactNode;
  value: AiSpendContextValue;
}> = ({ children, value }) => (
  <AiSpendContext.Provider value={value}>{children}</AiSpendContext.Provider>
);

export const useAiSpend = (): AiSpendContextValue => useContext(AiSpendContext);
