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
import { useShallow } from "zustand/react/shallow";

import {
  selectAddSectionAtPosition,
  selectDeleteSection,
  selectReorderSections,
  selectSectionIds,
  selectUpdateSection,
  useDashboardStore,
} from "@/store/DashboardStore";
import DashboardSection from "./DashboardSection";

const MemoizedSortableContext = memo(SortableContext);

interface DashboardSectionsContainerProps {}

const DashboardSectionsContainer: React.FunctionComponent<
  DashboardSectionsContainerProps
> = () => {
  const [active, setActive] = useState<Active | null>(null);
  const dragTopCoordinate = React.useRef<number>(0);

  const sectionIds = useDashboardStore(useShallow(selectSectionIds));

  const reorderSections = useDashboardStore(selectReorderSections);
  const addSectionAtPosition = useDashboardStore(selectAddSectionAtPosition);
  const deleteSection = useDashboardStore(selectDeleteSection);
  const updateSection = useDashboardStore(selectUpdateSection);

  const filteredWidgetsMap = useDashboardStore(
    useShallow((state) => state.getFilteredWidgetsMap(state.search)),
  );

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

  const activeSectionIndex = useMemo(() => {
    if (!active) return -1;
    return sectionIds.findIndex((id) => id === active.id);
  }, [active, sectionIds]);

  const activeSectionFilterMap = useMemo(() => {
    if (!active?.id) return undefined;
    return filteredWidgetsMap[active.id as string];
  }, [active?.id, filteredWidgetsMap]);

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
      const index = sectionIds.findIndex((id) => id === sectionId);
      if (index !== -1) {
        addSectionAtPosition(index);
      }
    },
    [sectionIds, addSectionAtPosition],
  );

  const handleAddSectionBelow = useCallback(
    (sectionId: string) => {
      const index = sectionIds.findIndex((id) => id === sectionId);
      if (index !== -1) {
        addSectionAtPosition(index + 1);
      }
    },
    [sectionIds, addSectionAtPosition],
  );

  return (
    <DndContext
      sensors={sensors}
      measuring={measuringConfig}
      collisionDetection={closestCenter}
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
              widgetFilterMap={filteredWidgetsMap[sectionId]}
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
            widgetFilterMap={activeSectionFilterMap}
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

export default memo(DashboardSectionsContainer);
