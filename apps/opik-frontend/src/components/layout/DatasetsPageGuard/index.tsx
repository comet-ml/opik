import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/components/layout/NoAccessPageGuard/NoAccessPageGuard";

const DatasetsPageGuard = () => {
  const {
    permissions: { canViewDatasets },
  } = usePermissions();

  return (
    <NoAccessPageGuard resourceName="datasets" canViewPage={canViewDatasets} />
  );
};

export default DatasetsPageGuard;
