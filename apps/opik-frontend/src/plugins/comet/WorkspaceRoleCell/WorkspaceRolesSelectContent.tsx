import { SelectContent, SelectItem } from "@/components/ui/select";
import { WorkspaceRole } from "@/plugins/comet/types";

interface WorkspaceRolesSelectContentProps {
  roles: WorkspaceRole[];
}

const WorkspaceRolesSelectContent = ({
  roles,
}: WorkspaceRolesSelectContentProps) => {
  return (
    <SelectContent align="start" position="popper" className="w-[280px]">
      {roles.map((role) => (
        <SelectItem
          key={role.roleId}
          value={role.roleId}
          description={role.description}
        >
          <span className="font-medium">{role.roleName}</span>
        </SelectItem>
      ))}
    </SelectContent>
  );
};

export default WorkspaceRolesSelectContent;
