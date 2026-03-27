import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";

const PlaygroundPageGuard = () => {
  const {
    permissions: { canUsePlayground },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      canViewPage={canUsePlayground}
      message="You don't have permissions to use playground in this workspace."
    />
  );
};

export default PlaygroundPageGuard;
