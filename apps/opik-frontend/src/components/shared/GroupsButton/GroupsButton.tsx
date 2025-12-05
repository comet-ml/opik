import React, { useCallback, useEffect, useMemo, useState } from "react";
import uniq from "lodash/uniq";
import isEmpty from "lodash/isEmpty";

import { GroupIcon, Plus } from "lucide-react";
import {
  DndContext,
  closestCenter,
  useSensor,
  useSensors,
  MouseSensor,
} from "@dnd-kit/core";
import {
  arrayMove,
  SortableContext,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import type { DragEndEvent } from "@dnd-kit/core/dist/types";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Button, ButtonProps } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { Group, GroupRowConfig, Groups } from "@/types/groups";
import { ColumnData } from "@/types/shared";
import useDeepMemo from "@/hooks/useDeepMemo";
import { cn } from "@/lib/utils";
import { createEmptyGroup, isGroupValid } from "@/lib/groups";
import GroupRow from "@/components/shared/GroupsContent/GroupRow";
import { MAX_GROUP_LEVELS } from "@/constants/groups";

const hasDuplication = (groups: Groups, group: Group, index: number) => {
  return (
    !isEmpty(group.field) &&
    !isEmpty(group.key) &&
    groups.findIndex((g) => g.field === group.field && g.key === group.key) <
      index
  );
};

type GroupsButtonConfig = {
  rowsMap: Record<string, GroupRowConfig>;
};

type GroupsButtonProps<TColumnData> = {
  columns: ColumnData<TColumnData>[];
  config?: GroupsButtonConfig;
  groups: Groups;
  onChange: (groups: Groups) => void;
  layout?: "standard" | "icon";
  variant?: ButtonProps["variant"];
  align?: "start" | "end";
  disabled?: boolean;
};

const GroupsButton = <TColumnData,>({
  groups: initialGroups,
  config,
  columns,
  onChange,
  layout = "standard",
  variant = "outline",
  align = "start",
  disabled,
}: GroupsButtonProps<TColumnData>) => {
  const [groups, setGroups] = useState<Groups>(initialGroups);
  const [open, setOpen] = useState(false);
  const isIconLayout = layout === "icon";

  const sensors = useSensors(
    useSensor(MouseSensor, {
      activationConstraint: {
        distance: 2,
      },
    }),
  );

  const validGroups = useDeepMemo(() => {
    return groups
      .filter(isGroupValid)
      .filter((g, index, self) => !hasDuplication(self, g, index));
  }, [groups]);

  useEffect(() => {
    if (!open) {
      if (initialGroups.length === 0) {
        setGroups([createEmptyGroup()]);
      } else {
        setGroups(initialGroups);
      }
    }
  }, [initialGroups, open]);

  useEffect(() => {
    return onChange(validGroups);
  }, [validGroups, onChange]);

  const clearHandler = useCallback(() => {
    setGroups([]);
  }, []);

  const addHandler = useCallback(() => {
    setGroups((state) => {
      if (state.length >= MAX_GROUP_LEVELS) {
        return state;
      }
      return [...state, createEmptyGroup()];
    });
  }, []);

  const onRemoveRow = useCallback((id: string) => {
    setGroups((state) => state.filter((f) => f.id !== id));
  }, []);

  const onChangeRow = useCallback((updateGroup: Group) => {
    setGroups((state) =>
      state.map((f) => (updateGroup.id === f.id ? updateGroup : f)),
    );
  }, []);

  const handleDragEnd = useCallback((event: DragEndEvent) => {
    const { active, over } = event;

    if (over && active.id !== over.id) {
      setGroups((items) => {
        const oldIndex = items.findIndex((item) => item.id === active.id);
        const newIndex = items.findIndex((item) => item.id === over.id);
        return arrayMove(items, oldIndex, newIndex);
      });
    }
  }, []);

  const disabledColumns = useMemo(() => {
    const disposableColumns = columns
      .filter((c) => c.disposable)
      .map((c) => c.id);

    if (!disposableColumns.length) {
      return undefined;
    }

    const columnsWithGroups = uniq(groups.map((f) => f.field));

    return disposableColumns.filter((c) => columnsWithGroups.includes(c));
  }, [groups, columns]);

  const getConfig = useCallback(
    (field: string) => config?.rowsMap[field],
    [config],
  );

  const renderGroups = () => {
    return groups.map((group, index) => {
      const prefix = index === 0 ? "By" : "And";

      const error = hasDuplication(groups, group, index)
        ? "Duplicate group with same field and key"
        : undefined;

      return (
        <GroupRow
          key={group.id}
          columns={columns}
          getConfig={getConfig}
          disabledColumns={disabledColumns}
          group={group}
          prefix={prefix}
          error={error}
          onRemove={onRemoveRow}
          onChange={onChangeRow}
          disabledSorting={groups.length <= 1}
        />
      );
    });
  };

  return (
    <Popover onOpenChange={setOpen} open={open}>
      <PopoverTrigger asChild>
        <Button
          variant={variant}
          size="sm"
          className={cn(
            isIconLayout && !validGroups.length && "size-8 px-0",
            isIconLayout && validGroups.length && "px-3",
          )}
          disabled={disabled}
        >
          <GroupIcon className="size-3.5 shrink-0" />
          {isIconLayout ? (
            validGroups.length ? (
              <span className="ml-1.5">{validGroups.length}</span>
            ) : null
          ) : (
            <span className="ml-1.5">{`Groups (${validGroups.length})`}</span>
          )}
        </Button>
      </PopoverTrigger>
      <PopoverContent className="min-w-[340px] px-8 py-6" align={align}>
        <div className="flex flex-col gap-1">
          <div className="flex items-center justify-between pb-1">
            <span className="comet-title-s">Groups</span>
            <Button
              variant="ghost"
              size="sm"
              className="-mr-2.5"
              onClick={clearHandler}
            >
              Clear all
            </Button>
          </div>
          <Separator />
          <div className="-mr-1 max-h-[50vh] overflow-y-auto overflow-x-hidden py-4">
            <DndContext
              sensors={sensors}
              collisionDetection={closestCenter}
              onDragEnd={handleDragEnd}
            >
              <SortableContext
                items={groups}
                strategy={verticalListSortingStrategy}
              >
                <table className="table-auto overflow-hidden">
                  <tbody>{renderGroups()}</tbody>
                </table>
              </SortableContext>
            </DndContext>
          </div>
          <div className="flex items-center">
            <Button
              variant="secondary"
              onClick={addHandler}
              disabled={groups.length >= MAX_GROUP_LEVELS}
            >
              <Plus className="mr-2 size-4" />
              Add group
            </Button>
            {groups.length >= MAX_GROUP_LEVELS && (
              <span className="ml-2 text-xs text-gray-500">
                Maximum {MAX_GROUP_LEVELS} levels reached
              </span>
            )}
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default GroupsButton;
