import React from "react";
import { Group } from "@/types/groups";
import SortDirectionSelector from "@/components/shared/GroupsButton/SortDirectionSelector";

type DefaultRowProps = {
  group: Group;
  onChange: (group: Group) => void;
};

export const DefaultRow: React.FC<DefaultRowProps> = ({ group, onChange }) => {
  return (
    <>
      <td className="p-1">
        <SortDirectionSelector
          direction={group.direction}
          onSelect={(d) => onChange({ ...group, direction: d })}
        />
      </td>
      <td className="p-1"></td>
    </>
  );
};

export default DefaultRow;
