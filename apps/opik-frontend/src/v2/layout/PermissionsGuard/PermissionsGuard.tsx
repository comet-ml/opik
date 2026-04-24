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

  // On landing routes (/get-started), render immediately. The permissions
  // query still runs in the background; checkNullablePermission defaults
  // can* flags to `true` while pending, so children see permissive
  // defaults during the load. Admin users don't wait today anyway.
  if (isPending && !isLandingRoute(pathname)) {
    return <Loader />;
  }

  return <>{children}</>;
};

export default PermissionsGuard;
