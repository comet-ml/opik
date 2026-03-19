import { TabsTrigger } from "@/ui/tabs";
import useUserPermission from "./useUserPermission";

export interface CollaboratorsTabTriggerProps {
  value: string;
}

const CollaboratorsTabTrigger = ({ value }: CollaboratorsTabTriggerProps) => {
  const { canUpdateUserRole } = useUserPermission();

  if (!canUpdateUserRole) {
    return null;
  }

  return (
    <TabsTrigger variant="underline" value={value}>
      Members
    </TabsTrigger>
  );
};

export default CollaboratorsTabTrigger;
