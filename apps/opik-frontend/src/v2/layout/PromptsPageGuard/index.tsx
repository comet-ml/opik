import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";

const PromptsPageGuard = () => {
  const {
    permissions: { canViewPrompts },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      canViewPage={canViewPrompts}
      message="You don't have permissions to view the prompt library in this workspace."
    />
  );
};

export default PromptsPageGuard;
