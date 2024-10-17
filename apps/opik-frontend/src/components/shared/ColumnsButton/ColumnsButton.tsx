import React, { useCallback, useMemo } from "react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { ColumnData } from "@/types/shared";
import { Columns3 } from "lucide-react";
import { sortColumnsByOrder } from "@/lib/table";
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
import SortableMenuItem from "@/components/shared/ColumnsButton/SortableMenuItem";

export type ColumnsButtonProps<TColumnData> = {
  columns: ColumnData<TColumnData>[];
  selectedColumns: string[];
  order: string[];
  onOrderChange: (order: string[]) => void;
  onSelectionChange: (selectedColumns: string[]) => void;
};

const ColumnsButton = <TColumnData,>({
  columns,
  selectedColumns,
  onSelectionChange,
  order,
  onOrderChange,
}: ColumnsButtonProps<TColumnData>) => {
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
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="secondary">
          <Columns3 className="mr-2 size-4" />
          Columns
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent className="max-h-[60vh] w-56 overflow-y-auto">
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
              />
            ))}
          </SortableContext>
        </DndContext>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default ColumnsButton;
