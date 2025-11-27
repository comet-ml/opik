import React, { useCallback, useMemo, memo } from "react";
import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { useShallow } from "zustand/react/shallow";

import { DashboardSection as DashboardSectionType } from "@/types/dashboard";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  CustomAccordionTrigger,
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

  const { sectionTitle, sectionExpanded, widgets, layout } = useDashboardStore(
    useShallow((state) => {
      const section = state.sections.find((s) => s.id === sectionId);
      return {
        sectionTitle: section?.title ?? "Untitled Section",
        sectionExpanded: section?.expanded ?? false,
        widgets: get(section, "widgets", []),
        layout: get(section, "layout", []),
      };
    }),
  );

  const widgetIds = useMemo(() => widgets.map((w) => w.id), [widgets]);

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

  const handleValueChange = useCallback(
    (value: string) => {
      const newExpanded = value === sectionId;
      onUpdateSection(sectionId, { expanded: newExpanded });
    },
    [sectionId, onUpdateSection],
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
        value={sectionExpanded ? sectionId : ""}
        onValueChange={handleValueChange}
        className="w-full"
      >
        <AccordionItem
          value={sectionId}
          className="border-t border-b-transparent"
        >
          <CustomAccordionTrigger className="group h-14 px-3 py-4">
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
          </CustomAccordionTrigger>
          {!isSorting && !isDragPreview && (
            <AccordionContent className="px-3 pb-3 pt-0">
              <DashboardWidgetGrid
                sectionId={sectionId}
                widgets={widgets.filter((w) => filteredWidgets.includes(w.id))}
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
