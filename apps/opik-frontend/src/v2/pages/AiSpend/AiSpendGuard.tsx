import React from "react";
import { Navigate, Outlet } from "@tanstack/react-router";
import Loader from "@/shared/Loader/Loader";
import { useActiveWorkspaceName } from "@/store/AppStore";
import { useAiSpend } from "@/contexts/AiSpendContext";

const AiSpendGuard: React.FC = () => {
  const workspaceName = useActiveWorkspaceName();
  const { isPending, hasAccess } = useAiSpend();

  if (isPending) {
    return <Loader />;
  }

  if (!hasAccess) {
    return <Navigate to="/$workspaceName/home" params={{ workspaceName }} />;
  }

  return <Outlet />;
};

export default AiSpendGuard;
