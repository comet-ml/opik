import React, { useCallback, useMemo } from "react";
import filter from "lodash/filter";
import uniq from "lodash/uniq";
import {
  DndContext,
  closestCenter,
  useSensor,
  useSensors,
  MouseSensor,
} from "@dnd-kit/core";
import { restrictToVerticalAxis } from "@dnd-kit/modifiers";
import {
  arrayMove,
  SortableContext,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import type { DragEndEvent } from "@dnd-kit/core/dist/types";
import { Group, GroupRowConfig, Groups } from "@/types/groups";
import { ColumnData, OnChangeFn } from "@/types/shared";
import GroupRow from "@/components/shared/GroupsContent/GroupRow";
import { cn } from "@/lib/utils";

type GroupsContentProps<TColumnData> = {
  groups: Groups;
  setGroups: OnChangeFn<Groups>;
  columns: ColumnData<TColumnData>[];
  config?: { rowsMap: Record<string, GroupRowConfig> };
  className?: string;
  hideSorting?: boolean;
};

const GroupsContent = <TColumnData,>({
  groups,
  setGroups,
  columns,
  config,
  className,
  hideSorting = false,
}: GroupsContentProps<TColumnData>) => {
  const sensors = useSensors(
    useSensor(MouseSensor, {
      activationConstraint: {
        distance: 2,
      },
    }),
  );

  const onRemoveRow = useCallback(
    (id: string) => {
      setGroups((prev) => filter(prev, (g) => g.id !== id));
    },
    [setGroups],
  );

  const onChangeRow = useCallback(
    (updateGroup: Group) => {
      setGroups((prev) =>
        prev.map((g) => (updateGroup.id === g.id ? updateGroup : g)),
      );
    },
    [setGroups],
  );

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event;

      if (over && active.id !== over.id) {
        setGroups((items) => {
          const oldIndex = items.findIndex((item) => item.id === active.id);
          const newIndex = items.findIndex((item) => item.id === over.id);
          return arrayMove(items, oldIndex, newIndex);
        });
      }
    },
    [setGroups],
  );

  const disabledColumns = useMemo(() => {
    const disposableColumns = columns
      .filter((c) => c.disposable)
      .map((c) => c.id);

    if (!disposableColumns.length) {
      return undefined;
    }

    const columnsWithGroups = uniq(groups.map((g) => g.field));

    return disposableColumns.filter((c) => columnsWithGroups.includes(c));
  }, [groups, columns]);

  const getConfig = useCallback(
    (field: string) => config?.rowsMap[field],
    [config],
  );

  const renderGroups = () => {
    return groups.map((group, index) => {
      const prefix = index === 0 ? "By" : "And";

      return (
        <GroupRow
          key={group.id}
          columns={columns}
          getConfig={getConfig}
          disabledColumns={disabledColumns}
          group={group}
          prefix={prefix}
          onRemove={onRemoveRow}
          onChange={onChangeRow}
          disabledSorting={hideSorting || groups.length <= 1}
          hideSorting={hideSorting}
        />
      );
    });
  };

  return (
    <div className={cn("overflow-y-auto overflow-x-hidden py-4", className)}>
      <DndContext
        sensors={sensors}
        collisionDetection={closestCenter}
        modifiers={[restrictToVerticalAxis]}
        onDragEnd={handleDragEnd}
      >
        <SortableContext items={groups} strategy={verticalListSortingStrategy}>
          <table className="table-auto">
            <tbody>{renderGroups()}</tbody>
          </table>
        </SortableContext>
      </DndContext>
    </div>
  );
};

export default GroupsContent;
