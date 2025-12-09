import React, { useCallback } from "react";
import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { X, GripVertical } from "lucide-react";
import isFunction from "lodash/isFunction";

import { cn } from "@/lib/utils";
import { Group, GroupRowConfig } from "@/types/groups";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import ColumnSelector from "@/components/shared/ColumnSelector/ColumnSelector";
import { Button } from "@/components/ui/button";
import DictionaryRow from "@/components/shared/GroupsContent/rows/DictionaryRow";
import DefaultRow from "@/components/shared/GroupsContent/rows/DefaultRow";
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
  hideSorting?: boolean;
  error?: string;
};

export const GroupRow = <TColumnData,>({
  group,
  columns,
  prefix,
  getConfig,
  disabledColumns,
  onRemove,
  onChange: onGroupChange,
  disabledSorting = false,
  hideSorting = false,
  error,
}: GroupRowProps<TColumnData>) => {
  const { active, attributes, listeners, setNodeRef, transform, transition } =
    useSortable({ id: group.id });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  const rowError = error ?? group.error;

  const onChange = useCallback(
    (newGroup: Group) => {
      const config = getConfig?.(newGroup.field);

      if (isFunction(config?.validateGroup)) {
        return onGroupChange({
          ...newGroup,
          error: config!.validateGroup(newGroup),
        });
      }

      return onGroupChange(newGroup);
    },
    [getConfig, onGroupChange],
  );

  const renderByType = () => {
    const config = getConfig?.(group.field);

    switch (group.type) {
      case COLUMN_TYPE.dictionary:
      case COLUMN_TYPE.numberDictionary:
        return (
          <DictionaryRow
            group={group}
            onChange={onChange}
            config={config}
            hideSorting={hideSorting}
          />
        );
      default:
        return (
          <DefaultRow
            group={group}
            onChange={onChange}
            config={config}
            hideSorting={hideSorting}
          />
        );
    }
  };

  return (
    <>
      <tr
        ref={setNodeRef}
        style={style}
        {...attributes}
        className={cn({
          "z-10": group.id === active?.id,
        })}
      >
        <td className="comet-body-s p-1">
          <div className="flex size-full items-center gap-1 pr-2">
            {!disabledSorting && (
              <Button
                variant="minimal"
                size="icon-xs"
                className="cursor-move "
                {...listeners}
              >
                <GripVertical className="size-3" />
              </Button>
            )}
            {prefix}
          </div>
        </td>
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
          </div>
        </td>
      </tr>
      {rowError && (
        <tr>
          <td
            colSpan={5}
            className="comet-body-xs max-w-56 p-1 text-destructive"
          >
            {rowError}
          </td>
        </tr>
      )}
    </>
  );
};

export default GroupRow;
