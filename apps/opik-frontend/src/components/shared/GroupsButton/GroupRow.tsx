import React from "react";
import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { X, GripHorizontal } from "lucide-react";

import { cn } from "@/lib/utils";
import { Group, GroupRowConfig } from "@/types/groups";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import ColumnSelector from "@/components/shared/ColumnSelector/ColumnSelector";
import { Button } from "@/components/ui/button";
import DictionaryRow from "@/components/shared/GroupsButton/rows/DictionaryRow";
import DefaultRow from "@/components/shared/GroupsButton/rows/DefaultRow";
import { createEmptyGroup } from "@/lib/groups";

type GroupRowProps<TColumnData> = {
  prefix: string;
  columns: ColumnData<TColumnData>[];
  getConfig?: (field: string) => GroupRowConfig | undefined;
  disabledColumns?: string[];
  group: Group;
  onRemove: (id: string) => void;
  onChange: (group: Group) => void;
  disabledSorting?: boolean;
};

export const GroupRow = <TColumnData,>({
  group,
  columns,
  prefix,
  getConfig,
  disabledColumns,
  onRemove,
  onChange,
  disabledSorting = false,
}: GroupRowProps<TColumnData>) => {
  const { active, attributes, listeners, setNodeRef, transform, transition } =
    useSortable({ id: group.id });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  const renderByType = () => {
    const config = getConfig?.(group.field);

    switch (group.type) {
      case COLUMN_TYPE.dictionary:
      case COLUMN_TYPE.numberDictionary:
        return (
          <DictionaryRow group={group} onChange={onChange} config={config} />
        );
      default:
        return <DefaultRow group={group} onChange={onChange} />;
    }
  };

  return (
    <tr
      ref={setNodeRef}
      style={style}
      {...attributes}
      className={cn("group", {
        "z-10": group.id === active?.id,
      })}
    >
      <td className="comet-body-s p-1">{prefix}</td>
      <td className="p-1">
        <ColumnSelector
          columns={columns}
          field={group.field}
          onSelect={(column) =>
            onChange({
              ...createEmptyGroup(),
              id: group.id,
              field: column.id,
              type: column.type as COLUMN_TYPE,
            })
          }
          disabledColumns={disabledColumns}
        ></ColumnSelector>
      </td>
      {renderByType()}
      <td className="relative">
        <div className="flex items-center gap-1">
          <Button
            variant="minimal"
            size="icon-xs"
            onClick={() => onRemove(group.id)}
          >
            <X />
          </Button>
          {!disabledSorting && (
            <Button
              variant="ghost"
              size="icon-xs"
              className="cursor-move opacity-0 group-hover:opacity-100"
              {...listeners}
            >
              <GripHorizontal className="size-3" />
            </Button>
          )}
        </div>
      </td>
    </tr>
  );
};

export default GroupRow;
