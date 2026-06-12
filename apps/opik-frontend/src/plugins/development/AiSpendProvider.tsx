import React, { ReactNode } from "react";
import { AiSpendProvider as BaseAiSpendProvider } from "@/contexts/AiSpendContext";
import { AI_SPEND_PROJECT_NAME } from "@/lib/aiSpend";

// Local-dev stand-in for the comet AiSpendProvider plugin: grants access
// unconditionally so the AI Spend pages are reachable against the local
// backend (no organizations/enterprise-plan API exists in OSS mode).
const AiSpendProvider: React.FC<{ children: ReactNode }> = ({ children }) => (
  <BaseAiSpendProvider
    value={{
      isPending: false,
      hasAccess: true,
      spendWorkspaceName: "default",
      organizationName: "local",
      projectName: AI_SPEND_PROJECT_NAME,
    }}
  >
    {children}
  </BaseAiSpendProvider>
);

export default AiSpendProvider;
