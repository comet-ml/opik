import React from "react";
import { Navigate, Outlet } from "@tanstack/react-router";
import Loader from "@/shared/Loader/Loader";
import { useOpikWorkspaceName } from "@/store/AppStore";
import { useAiSpend } from "@/contexts/AiSpendContext";

const AiSpend: React.FC = () => {
  const opikWorkspaceName = useOpikWorkspaceName();
  const { isPending, hasAccess, isSpendWorkspaceActive } = useAiSpend();

  if (isPending) {
    return <Loader />;
  }

  if (!hasAccess || !isSpendWorkspaceActive) {
    return (
      <Navigate
        to="/$workspaceName/home"
        params={{ workspaceName: opikWorkspaceName }}
      />
    );
  }

  return <Outlet />;
};

export default AiSpend;
