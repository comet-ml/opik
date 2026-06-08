import React, { createContext, useContext, ReactNode } from "react";

export type AiSpendContextValue = {
  isPending: boolean;
  hasAccess: boolean;
  spendWorkspaceName?: string;
  organizationName?: string;
};

const DEFAULT_VALUE: AiSpendContextValue = {
  isPending: false,
  hasAccess: true, // TODO lala
};

const AiSpendContext = createContext<AiSpendContextValue>(DEFAULT_VALUE);

export const AiSpendProvider: React.FC<{
  children: ReactNode;
  value: AiSpendContextValue;
}> = ({ children, value }) => (
  <AiSpendContext.Provider value={value}>{children}</AiSpendContext.Provider>
);

export const useAiSpend = (): AiSpendContextValue => useContext(AiSpendContext);
