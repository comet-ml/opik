import React, { useCallback, useMemo } from "react";
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

import { ColumnData } from "@/types/shared";
import { sortColumnsByOrder } from "@/lib/table";
import SortableMenuItem from "./SortableMenuItem";

export type SortableMenuSectionProps<TColumnData> = {
  selectedColumns: string[];
  onSelectionChange: (selectedColumns: string[]) => void;
  columns: ColumnData<TColumnData>[];
  order: string[];
  onOrderChange: (order: string[]) => void;
  disabledSorting?: boolean;
};

const SortableMenuSection = <TColumnData,>({
  columns,
  selectedColumns,
  onSelectionChange,
  order,
  onOrderChange,
  disabledSorting,
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
      const localOrder = orderedColumns.map((c) => c.id);

      if (over && active.id !== over.id) {
        const oldIndex = localOrder.indexOf(active.id as string);
        const newIndex = localOrder.indexOf(over.id as string);
        onOrderChange(arrayMove(localOrder, oldIndex, newIndex));
      }
    },
    [onOrderChange, orderedColumns],
  );

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
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
          />
        ))}
      </SortableContext>
    </DndContext>
  );
};

export default SortableMenuSection;
