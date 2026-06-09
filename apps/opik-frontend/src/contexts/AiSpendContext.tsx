import React, { createContext, useContext, ReactNode } from "react";

export type AiSpendContextValue = {
  isPending: boolean;
  hasAccess: boolean;
  spendWorkspaceName?: string;
  organizationName?: string;
  projectName: string;
};

// Production fallback (used when the comet AiSpendProvider is absent, e.g. OSS):
// the area stays locked. The comet plugin overrides this with real values.
// const DEFAULT_VALUE: AiSpendContextValue = {
//   isPending: false,
//   hasAccess: false,
//   projectName: "",
// };

// Local testing against a dev-runner backend (no comet plugin): unlock the area
// and point it at local data, then open `/<activeWorkspace>/ai-spend/home`. With
// dev-runner the active workspace is `default`, so the URL is
// `/default/ai-spend/home`.
//   - hasAccess: true        unlocks the user menu item + routes
//   - spendWorkspaceName     workspace the AI Spend API is queried against
//   - projectName            project the spend metrics are read from
//   - organizationName       label shown in the AI Spend sidebar
// Swap back to the locked fallback above before merging.
const DEFAULT_VALUE: AiSpendContextValue = {
  isPending: false,
  hasAccess: true,
  spendWorkspaceName: "default",
  organizationName: "Test feature",
  projectName: "claude-code",
};

const AiSpendContext = createContext<AiSpendContextValue>(DEFAULT_VALUE);

export const AiSpendProvider: React.FC<{
  children: ReactNode;
  value: AiSpendContextValue;
}> = ({ children, value }) => (
  <AiSpendContext.Provider value={value}>{children}</AiSpendContext.Provider>
);

export const useAiSpend = (): AiSpendContextValue => useContext(AiSpendContext);
