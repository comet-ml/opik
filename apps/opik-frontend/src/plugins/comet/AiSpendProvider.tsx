import React, { ReactNode, useMemo } from "react";
import { AiSpendProvider as BaseAiSpendProvider } from "@/contexts/AiSpendContext";
import useAiSpendManager from "./useAiSpendManager";

const AiSpendProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const { isPending, hasAccess, spendWorkspaceName, organization } =
    useAiSpendManager();

  const value = useMemo(
    () => ({
      isPending,
      hasAccess,
      spendWorkspaceName,
      organizationName: organization?.name,
    }),
    [isPending, hasAccess, spendWorkspaceName, organization?.name],
  );

  return <BaseAiSpendProvider value={value}>{children}</BaseAiSpendProvider>;
};

export default AiSpendProvider;
