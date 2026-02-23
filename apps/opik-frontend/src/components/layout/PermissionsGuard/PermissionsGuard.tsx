import React from "react";
import { usePermissions } from "@/contexts/PermissionsContext";
import Loader from "@/components/shared/Loader/Loader";

interface PermissionsGuardProps {
  children: React.ReactNode;
}

const PermissionsGuard: React.FC<PermissionsGuardProps> = ({ children }) => {
  const { isPending } = usePermissions();

  if (isPending) {
    return <Loader />;
  }

  return <>{children}</>;
};

export default PermissionsGuard;
