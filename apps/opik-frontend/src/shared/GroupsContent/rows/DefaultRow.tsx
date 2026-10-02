import React from "react";
import { Group, GroupRowConfig } from "@/types/groups";
import SortDirectionSelector from "@/shared/GroupsContent/SortDirectionSelector";

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
          {!group.field ? null : config?.sortingMessage ? (
            // A fixed sort order isn't selectable, so it's shown as text rather than an input.
            <div className="comet-body-s max-w-[300px] px-2 text-light-slate">
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
