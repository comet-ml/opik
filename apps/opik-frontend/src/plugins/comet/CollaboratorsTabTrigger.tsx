import { TabsTrigger } from "@/components/ui/tabs";
import useUserPermission from "./useUserPermission";

export interface CollaboratorsTabTriggerProps {
  value: string;
}

const CollaboratorsTabTrigger = ({ value }: CollaboratorsTabTriggerProps) => {
  const { isWorkspaceOwner } = useUserPermission();

  if (!isWorkspaceOwner) {
    return null;
  }

  return (
    <TabsTrigger variant="underline" value={value}>
      Members
    </TabsTrigger>
  );
};

export default CollaboratorsTabTrigger;
