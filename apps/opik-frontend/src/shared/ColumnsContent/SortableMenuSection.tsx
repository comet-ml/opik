import React, { useCallback, useMemo } from "react";
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

import { ColumnData } from "@/types/shared";
import { sortColumnsByOrder } from "@/lib/table";
import SortableMenuItem from "./SortableMenuItem";
import type { ColumnsContentVariant } from "./SortableMenuItem";

export type SortableMenuSectionProps<TColumnData> = {
  selectedColumns: string[];
  onSelectionChange: (selectedColumns: string[]) => void;
  columns: ColumnData<TColumnData>[];
  order: string[];
  onOrderChange: (order: string[]) => void;
  disabledSorting?: boolean;
  variant?: ColumnsContentVariant;
};

const SortableMenuSection = <TColumnData,>({
  columns,
  selectedColumns,
  onSelectionChange,
  order,
  onOrderChange,
  disabledSorting,
  variant,
}: SortableMenuSectionProps<TColumnData>) => {
  const sensors = useSensors(
    useSensor(MouseSensor, {
      activationConstraint: {
        distance: 2,
      },
    }),
  );

  const orderedColumns = useMemo(
    () => sortColumnsByOrder(columns, order),
    [columns, order],
  );

  const onCheckboxChange = useCallback(
    (id: string) => {
      const localSelectedColumns = selectedColumns.slice();
      const index = localSelectedColumns.indexOf(id);

      if (index !== -1) {
        localSelectedColumns.splice(index, 1);
      } else {
        localSelectedColumns.push(id);
      }

      onSelectionChange(localSelectedColumns);
    },
    [selectedColumns, onSelectionChange],
  );

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event;
      if (!over || active.id === over.id) return;

      const visibleIds = orderedColumns.map((c) => c.id);
      const oldIndex = visibleIds.indexOf(active.id as string);
      const newIndex = visibleIds.indexOf(over.id as string);
      const newVisibleOrder = arrayMove(visibleIds, oldIndex, newIndex);

      // Preserve IDs in `order` that aren't in `columns` (e.g. filtered-out
      // grouping anchor) by keeping their slots and only rewriting visible slots.
      const visibleSet = new Set(visibleIds);
      let cursor = 0;
      const merged: string[] = [];
      for (const id of order) {
        if (visibleSet.has(id)) {
          merged.push(newVisibleOrder[cursor++]);
        } else {
          merged.push(id);
        }
      }
      while (cursor < newVisibleOrder.length) {
        merged.push(newVisibleOrder[cursor++]);
      }
      onOrderChange(merged);
    },
    [onOrderChange, orderedColumns, order],
  );

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      modifiers={[restrictToVerticalAxis]}
      onDragEnd={handleDragEnd}
    >
      <SortableContext
        items={orderedColumns}
        strategy={verticalListSortingStrategy}
      >
        {orderedColumns.map((column) => (
          <SortableMenuItem
            key={column.id}
            id={column.id}
            label={column.label}
            checked={selectedColumns.includes(column.id)}
            onCheckboxChange={onCheckboxChange}
            disabled={column.disabled}
            disabledSorting={disabledSorting}
            variant={variant}
          />
        ))}
      </SortableContext>
    </DndContext>
  );
};

export default SortableMenuSection;
