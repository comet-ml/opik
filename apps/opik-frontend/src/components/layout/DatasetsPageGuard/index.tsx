import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/components/layout/NoAccessPageGuard/NoAccessPageGuard";

const ExperimentsPageGuard = () => {
  const {
    permissions: { canViewDatasets },
  } = usePermissions();

  return (
    <NoAccessPageGuard resourceName="datasets" canViewPage={canViewDatasets} />
  );
};

export default ExperimentsPageGuard;
