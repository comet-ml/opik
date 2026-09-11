import React, { ReactNode, useCallback, useMemo } from "react";
import { useNavigate } from "@tanstack/react-router";
import { AiSpendProvider as BaseAiSpendProvider } from "@/contexts/AiSpendContext";

// Local-dev stand-in for the comet AiSpendProvider plugin: grants access
// unconditionally so the AI Spend pages are reachable against the local
// backend (no organizations/enterprise-plan API exists in OSS mode).
const AiSpendProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const navigate = useNavigate();

  const goToCostIntelligence = useCallback(() => {
    navigate({
      to: "/$workspaceName/ai-spend/home",
      params: { workspaceName: "default" },
    });
  }, [navigate]);

  const value = useMemo(
    () => ({
      isPending: false,
      hasAccess: true,
      isOrganizationAdmin: true,
      spendWorkspaceName: "default",
      organizationName: "local",
      isSpendWorkspaceActive: true,
      goToCostIntelligence,
    }),
    [goToCostIntelligence],
  );

  return <BaseAiSpendProvider value={value}>{children}</BaseAiSpendProvider>;
};

export default AiSpendProvider;
