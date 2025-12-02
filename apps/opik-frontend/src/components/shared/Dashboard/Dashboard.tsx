import React, { memo, useCallback, useMemo, useState } from "react";
import type { Active, DragEndEvent, DragStartEvent } from "@dnd-kit/core";
import {
  closestCenter,
  DndContext,
  DragOverlay,
  getClientRect,
  MouseSensor,
  useSensor,
  useSensors,
} from "@dnd-kit/core";
import {
  SortableContext,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { restrictToVerticalAxis } from "@dnd-kit/modifiers";
import { useShallow } from "zustand/react/shallow";

import {
  selectAddSectionAtPosition,
  selectDeleteSection,
  selectReorderSections,
  selectSectionIds,
  selectUpdateSection,
  useDashboardStore,
} from "@/store/DashboardStore";
import DashboardSection from "./DashboardSection/DashboardSection";

const MemoizedSortableContext = memo(SortableContext);

const Dashboard: React.FunctionComponent = () => {
  const [active, setActive] = useState<Active | null>(null);
  const dragTopCoordinate = React.useRef<number>(0);

  const sectionIds = useDashboardStore(useShallow(selectSectionIds));

  const reorderSections = useDashboardStore(selectReorderSections);
  const addSectionAtPosition = useDashboardStore(selectAddSectionAtPosition);
  const deleteSection = useDashboardStore(selectDeleteSection);
  const updateSection = useDashboardStore(selectUpdateSection);

  const measuringConfig = useMemo(
    () => ({
      draggable: {
        measure: (element: HTMLElement) => {
          const rect = getClientRect(element);

          if (dragTopCoordinate.current === 0) {
            dragTopCoordinate.current = rect.top;
          }

          return {
            ...rect,
            top: dragTopCoordinate.current,
          };
        },
      },
    }),
    [],
  );

  const sensors = useSensors(
    useSensor(MouseSensor, {
      activationConstraint: {
        distance: 1,
      },
    }),
  );

  const activeSectionIndex = !active
    ? -1
    : sectionIds.findIndex((id) => id === active.id);

  const handleDragStart = useCallback((event: DragStartEvent) => {
    dragTopCoordinate.current = 0;
    setActive(event.active);
  }, []);

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event;

      if (over && active.id !== over.id) {
        reorderSections(active.id, over.id);
      }

      setActive(null);
    },
    [reorderSections],
  );

  const handleDragCancel = useCallback(() => {
    setActive(null);
  }, []);

  const handleAddSectionAbove = useCallback(
    (sectionId: string) => {
      const currentSectionIds = useDashboardStore
        .getState()
        .sections.map((s) => s.id);
      const index = currentSectionIds.findIndex((id) => id === sectionId);
      if (index !== -1) {
        addSectionAtPosition(index);
      }
    },
    [addSectionAtPosition],
  );

  const handleAddSectionBelow = useCallback(
    (sectionId: string) => {
      const currentSectionIds = useDashboardStore
        .getState()
        .sections.map((s) => s.id);
      const index = currentSectionIds.findIndex((id) => id === sectionId);
      if (index !== -1) {
        addSectionAtPosition(index + 1);
      }
    },
    [addSectionAtPosition],
  );

  return (
    <DndContext
      sensors={sensors}
      measuring={measuringConfig}
      collisionDetection={closestCenter}
      modifiers={[restrictToVerticalAxis]}
      onDragStart={handleDragStart}
      onDragEnd={handleDragEnd}
      onDragCancel={handleDragCancel}
    >
      <MemoizedSortableContext
        items={sectionIds}
        strategy={verticalListSortingStrategy}
      >
        <div>
          {sectionIds.map((sectionId) => (
            <DashboardSection
              key={sectionId}
              sectionId={sectionId}
              isLastSection={sectionIds.length === 1}
              onUpdateSection={updateSection}
              onDeleteSection={deleteSection}
              onAddSectionAbove={handleAddSectionAbove}
              onAddSectionBelow={handleAddSectionBelow}
            />
          ))}
        </div>
      </MemoizedSortableContext>

      <DragOverlay dropAnimation={null}>
        {active && activeSectionIndex !== -1 && (
          <DashboardSection
            sectionId={active.id as string}
            isLastSection={sectionIds.length === 1}
            onUpdateSection={updateSection}
            onDeleteSection={deleteSection}
            onAddSectionAbove={handleAddSectionAbove}
            onAddSectionBelow={handleAddSectionBelow}
            isDragPreview
          />
        )}
      </DragOverlay>
    </DndContext>
  );
};

export default memo(Dashboard);
