import React, { useCallback, useMemo, memo } from "react";
import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { useShallow } from "zustand/react/shallow";

import { DashboardSection as DashboardSectionType } from "@/types/dashboard";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { selectSearch, useDashboardStore } from "@/store/DashboardStore";
import { cn } from "@/lib/utils";
import DashboardSectionHeader from "./DashboardSectionHeader";
import DashboardWidgetGrid from "./DashboardWidgetGrid";
import get from "lodash/get";

interface DashboardSectionProps {
  sectionId: string;
  widgetFilterMap?: Record<string, boolean>;
  isLastSection: boolean;
  onUpdateSection: (
    sectionId: string,
    updates: Partial<DashboardSectionType>,
  ) => void;
  onDeleteSection: (sectionId: string) => void;
  onAddSectionAbove: (sectionId: string) => void;
  onAddSectionBelow: (sectionId: string) => void;
  isDragPreview?: boolean;
}

const DashboardSection: React.FunctionComponent<DashboardSectionProps> = ({
  sectionId,
  widgetFilterMap,
  isLastSection,
  onUpdateSection,
  onDeleteSection,
  onAddSectionAbove,
  onAddSectionBelow,
  isDragPreview = false,
}) => {
  const search = useDashboardStore(selectSearch);
  const isSearchMode = Boolean(search);
  // Selective subscriptions from store
  const sectionTitle = useDashboardStore(
    (state) =>
      state.sections.find((s) => s.id === sectionId)?.title ??
      "Untitled Section",
  );

  const sectionExpanded = useDashboardStore(
    (state) =>
      state.sections.find((s) => s.id === sectionId)?.expanded ?? false,
  );

  const widgetIds = useDashboardStore(
    useShallow((state) => {
      const section = state.sections.find((s) => s.id === sectionId);
      return get(section, "widgets", []).map((w) => w.id);
    }),
  );

  const layout = useDashboardStore(
    useShallow((state) => {
      const section = state.sections.find((s) => s.id === sectionId);
      return get(section, "layout", []);
    }),
  );

  const filteredWidgets = useMemo(() => {
    if (!widgetFilterMap) return widgetIds;
    return widgetIds.filter((id) => widgetFilterMap[id]);
  }, [widgetIds, widgetFilterMap]);

  const widgetCount = useMemo(
    () => (isSearchMode ? filteredWidgets.length : widgetIds.length),
    [isSearchMode, filteredWidgets.length, widgetIds.length],
  );

  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
    isSorting,
  } = useSortable({
    id: sectionId,
    disabled: isDragPreview,
  });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  };

  const handleToggleExpanded = useCallback(
    (e: React.MouseEvent) => {
      e.stopPropagation();
      onUpdateSection(sectionId, { expanded: !sectionExpanded });
    },
    [sectionId, sectionExpanded, onUpdateSection],
  );

  const handleUpdateTitle = useCallback(
    (title: string) => {
      onUpdateSection(sectionId, { title });
    },
    [sectionId, onUpdateSection],
  );

  const handleDeleteSection = useCallback(() => {
    onDeleteSection(sectionId);
  }, [sectionId, onDeleteSection]);

  const handleAddSectionAbove = useCallback(() => {
    onAddSectionAbove(sectionId);
  }, [sectionId, onAddSectionAbove]);

  const handleAddSectionBelow = useCallback(() => {
    onAddSectionBelow(sectionId);
  }, [sectionId, onAddSectionBelow]);

  const dragHandleProps = useMemo(
    () => ({
      ...attributes,
      ...listeners,
    }),
    [attributes, listeners],
  );

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={cn(
        isDragPreview && "opacity-50",
        isSorting && "dashboard-section-sorting",
      )}
    >
      <Accordion
        type="single"
        collapsible
        value={sectionExpanded ? sectionId : undefined}
        className="w-full"
      >
        <AccordionItem value={sectionId} className="border-y">
          <AccordionTrigger
            className="h-14 px-3 py-4 hover:no-underline [&>svg]:hidden"
            onClick={handleToggleExpanded}
          >
            <DashboardSectionHeader
              sectionId={sectionId}
              title={sectionTitle}
              widgetCount={widgetCount}
              expanded={sectionExpanded}
              isLastSection={isLastSection}
              dragHandleProps={dragHandleProps}
              onUpdateTitle={handleUpdateTitle}
              onDeleteSection={handleDeleteSection}
              onAddSectionAbove={handleAddSectionAbove}
              onAddSectionBelow={handleAddSectionBelow}
            />
          </AccordionTrigger>
          {!isSorting && !isDragPreview && (
            <AccordionContent className="px-3 pb-3 pt-0">
              <DashboardWidgetGrid
                sectionId={sectionId}
                widgetIds={filteredWidgets}
                layout={layout}
              />
            </AccordionContent>
          )}
        </AccordionItem>
      </Accordion>
    </div>
  );
};

const arePropsEqual = (
  prev: DashboardSectionProps,
  next: DashboardSectionProps,
) => {
  return (
    prev.sectionId === next.sectionId &&
    prev.isLastSection === next.isLastSection &&
    prev.isDragPreview === next.isDragPreview
  );
};

export default memo(DashboardSection, arePropsEqual);
