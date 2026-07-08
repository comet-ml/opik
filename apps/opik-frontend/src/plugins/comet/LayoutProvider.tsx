import React, { ReactNode, useCallback, useMemo } from "react";
import { useNavigate } from "@tanstack/react-router";
import { AiSpendProvider as BaseAiSpendProvider } from "@/contexts/AiSpendContext";
import useAppStore, {
  useActiveWorkspaceName,
  useSetPreviousWorkspaceName,
  usePreviousWorkspaceName,
} from "@/store/AppStore";
import useAiSpendManager from "./useAiSpendManager";
import useUser from "./useUser";

const AiSpendProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const {
    isPending,
    hasAccess,
    isOrganizationAdmin,
    spendWorkspaceName,
    isSpendWorkspaceActive,
    organization,
  } = useAiSpendManager();
  const previousWorkspaceName = usePreviousWorkspaceName();
  const { data: user } = useUser();

  const navigate = useNavigate();
  const activeWorkspaceName = useActiveWorkspaceName();
  const setPreviousWorkspaceName = useSetPreviousWorkspaceName();

  // Resolve the shared-navigation target synchronously during render (not in an
  // effect) so it is correct in the same commit the sidebar/header read it.
  // Otherwise there is a one-frame window right after entering the spend
  // workspace where the override is still null and `useOpikWorkspaceName()`
  // falls back to the active (hidden spend) workspace — chrome links built in
  // that frame point at `/$spendWs/home`, which renders "private project".
  const opikWorkspaceOverride = isSpendWorkspaceActive
    ? previousWorkspaceName ?? user?.defaultWorkspace ?? null
    : null;
  if (useAppStore.getState().opikWorkspaceOverride !== opikWorkspaceOverride) {
    useAppStore.getState().setOpikWorkspaceOverride(opikWorkspaceOverride);
  }

  const goToCostIntelligence = useCallback(() => {
    if (!spendWorkspaceName) return;
    if (!isSpendWorkspaceActive) {
      setPreviousWorkspaceName(activeWorkspaceName);
    }
    navigate({
      to: "/$workspaceName/ai-spend",
      params: { workspaceName: spendWorkspaceName },
    });
  }, [
    spendWorkspaceName,
    isSpendWorkspaceActive,
    activeWorkspaceName,
    setPreviousWorkspaceName,
    navigate,
  ]);

  const value = useMemo(
    () => ({
      isPending,
      hasAccess,
      isOrganizationAdmin,
      spendWorkspaceName,
      isSpendWorkspaceActive,
      organizationName: organization?.name,
      goToCostIntelligence,
    }),
    [
      isPending,
      hasAccess,
      isOrganizationAdmin,
      spendWorkspaceName,
      isSpendWorkspaceActive,
      organization?.name,
      goToCostIntelligence,
    ],
  );

  return <BaseAiSpendProvider value={value}>{children}</BaseAiSpendProvider>;
};

export default AiSpendProvider;
