import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";

const DatasetsPageGuard = () => {
  const {
    permissions: { canViewDatasets },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      resourceName="evaluation suites"
      canViewPage={canViewDatasets}
    />
  );
};

export default DatasetsPageGuard;
