import React from "react";
import { Group, GroupRowConfig } from "@/types/groups";
import SortDirectionSelector from "@/components/shared/GroupsContent/SortDirectionSelector";

type DefaultRowProps = {
  config?: GroupRowConfig;
  group: Group;
  onChange: (group: Group) => void;
  hideSorting?: boolean;
};

export const DefaultRow: React.FC<DefaultRowProps> = ({
  config,
  group,
  onChange,
  hideSorting = false,
}) => {
  return (
    <>
      <td className="p-1"></td>
      {!hideSorting && (
        <td className="p-1">
          {config?.sortingMessage ? (
            <div className="comet-body-s h-10 max-w-[300px] rounded-md border border-border p-2 text-light-slate">
              {config.sortingMessage}
            </div>
          ) : (
            <SortDirectionSelector
              direction={group.direction}
              onSelect={(d) => onChange({ ...group, direction: d })}
            />
          )}
        </td>
      )}
    </>
  );
};

export default DefaultRow;
