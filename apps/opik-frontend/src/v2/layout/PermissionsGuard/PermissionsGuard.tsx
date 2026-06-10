import React from "react";
import { useLocation } from "@tanstack/react-router";
import { usePermissions } from "@/contexts/PermissionsContext";
import { isLandingRoute } from "@/lib/landingRoutes";
import Loader from "@/shared/Loader/Loader";

interface PermissionsGuardProps {
  children: React.ReactNode;
}

const PermissionsGuard: React.FC<PermissionsGuardProps> = ({ children }) => {
  const { isPending } = usePermissions();
  const { pathname } = useLocation();

  if (isPending && !isLandingRoute(pathname)) {
    return <Loader />;
  }

  return <>{children}</>;
};

export default PermissionsGuard;
