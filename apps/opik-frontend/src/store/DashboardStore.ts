import { create } from "zustand";
import { devtools } from "zustand/middleware";
import { arrayMove } from "@dnd-kit/sortable";
import { UniqueIdentifier } from "@dnd-kit/core";
import uniqid from "uniqid";

import {
  DashboardLayout,
  DashboardRuntimeConfig,
  DashboardSection,
  DashboardSections,
  DashboardState,
  DashboardWidget,
  WidgetResolver,
  AddEditWidgetCallbackParams,
} from "@/types/dashboard";
import {
  generateEmptyDashboard,
  generateEmptySection,
  getSectionById,
  updateWidgetWithGeneratedTitle,
} from "@/lib/dashboard/utils";
import {
  calculateLayoutForAddingWidget,
  removeWidgetFromLayout,
  getLayoutItemSize,
} from "@/lib/dashboard/layout";
import { migrateDashboardConfig } from "@/lib/dashboard/migrations";

interface DashboardStoreState {
  sections: DashboardSections;
  version: number;
  lastModified: number;
  runtimeConfig: DashboardRuntimeConfig;
  onAddEditWidgetCallback:
    | ((params: AddEditWidgetCallbackParams) => void)
    | null;
  widgetResolver: WidgetResolver | null;
  previewWidget: DashboardWidget | null;
  readOnly: boolean;
}

interface DashboardActions {
  addSection: (title?: string) => string;
  addSectionAtPosition: (position: number, title?: string) => string;
  deleteSection: (sectionId: string) => void;
  updateSection: (
    sectionId: string,
    updates: Partial<DashboardSection>,
  ) => void;
  reorderSections: (
    activeId: UniqueIdentifier,
    overId: UniqueIdentifier,
  ) => void;

  addWidget: (
    sectionId: string,
    config: Omit<DashboardWidget, "id">,
  ) => string | undefined;
  duplicateWidget: (sectionId: string, widgetId: string) => string | undefined;
  deleteWidget: (sectionId: string, widgetId: string) => void;
  updateWidget: (
    sectionId: string,
    widgetId: string,
    config: Partial<DashboardWidget>,
  ) => void;
  moveWidget: (
    sourceSectionId: string,
    targetSectionId: string,
    widgetId: string,
  ) => void;
  updateLayout: (sectionId: string, layout: DashboardLayout) => void;

  setRuntimeConfig: (runtimeConfig: DashboardRuntimeConfig) => void;

  setOnAddEditWidgetCallback: (
    callback: ((params: AddEditWidgetCallbackParams) => void) | null,
  ) => void;

  setWidgetResolver: (resolver: WidgetResolver | null) => void;

  setPreviewWidget: (widget: DashboardWidget | null) => void;
  updatePreviewWidget: (data: Partial<DashboardWidget>) => void;

  setReadOnly: (readOnly: boolean) => void;

  clearDashboard: () => void;

  loadDashboardFromBackend: (dashboardState: DashboardState) => void;
  getDashboard: () => DashboardState;

  getSectionById: (sectionId: string) => DashboardSection | undefined;
  getWidgetById: (
    sectionId: string,
    widgetId: string,
  ) => DashboardWidget | undefined;
}

export type DashboardStore = DashboardStoreState & DashboardActions;

export const useDashboardStore = create<DashboardStore>()(
  devtools(
    (set, get) => {
      const initialDashboard = generateEmptyDashboard();

      const updateSectionById = (
        sectionId: string,
        updateFn: (section: DashboardSection) => DashboardSection,
        actionName: string,
      ) => {
        set(
          (state) => ({
            sections: state.sections.map((s) =>
              s.id === sectionId ? updateFn(s) : s,
            ),
            lastModified: Date.now(),
          }),
          false,
          actionName,
        );
      };

      return {
        sections: initialDashboard.sections,
        version: initialDashboard.version,
        lastModified: initialDashboard.lastModified,
        runtimeConfig: {},
        onAddEditWidgetCallback: null,
        widgetResolver: null,
        previewWidget: null,
        readOnly: false,

        addSection: (title?: string) => {
          const newSection = generateEmptySection(title);
          set(
            (state) => ({
              sections: [...state.sections, newSection],
              lastModified: Date.now(),
            }),
            false,
            "addSection",
          );
          return newSection.id;
        },

        addSectionAtPosition: (position: number, title?: string) => {
          const newSection = generateEmptySection(title);
          set(
            (state) => {
              const sections = [...state.sections];
              sections.splice(position, 0, newSection);
              return {
                sections,
                lastModified: Date.now(),
              };
            },
            false,
            "addSectionAtPosition",
          );
          return newSection.id;
        },

        deleteSection: (sectionId: string) => {
          set(
            (state) => {
              // Prevent deleting the last section
              if (state.sections.length <= 1) {
                console.warn("Cannot delete the last section");
                return state;
              }

              return {
                sections: state.sections.filter((s) => s.id !== sectionId),
                lastModified: Date.now(),
              };
            },
            false,
            "deleteSection",
          );
        },

        updateSection: (
          sectionId: string,
          updates: Partial<DashboardSection>,
        ) => {
          updateSectionById(
            sectionId,
            (section) => ({ ...section, ...updates }),
            "updateSection",
          );
        },

        reorderSections: (
          activeId: UniqueIdentifier,
          overId: UniqueIdentifier,
        ) => {
          set(
            (state) => {
              if (activeId === overId) return state;

              const oldIndex = state.sections.findIndex(
                (s) => s.id === activeId,
              );
              const newIndex = state.sections.findIndex((s) => s.id === overId);

              if (oldIndex === -1 || newIndex === -1) return state;

              return {
                sections: arrayMove(state.sections, oldIndex, newIndex),
                lastModified: Date.now(),
              };
            },
            false,
            "reorderSections",
          );
        },

        addWidget: (sectionId: string, widgetConfig) => {
          const state = get();
          const section = getSectionById(state.sections, sectionId);
          if (!section) return undefined;

          const newWidget: DashboardWidget = {
            ...widgetConfig,
            id: uniqid(),
          };

          const updatedLayout = calculateLayoutForAddingWidget(
            section.layout,
            newWidget,
          );

          updateSectionById(
            sectionId,
            (s) => ({
              ...s,
              widgets: [...s.widgets, newWidget],
              layout: updatedLayout,
            }),
            "addWidget",
          );

          return newWidget.id;
        },

        duplicateWidget: (sectionId: string, widgetId: string) => {
          const state = get();
          const section = getSectionById(state.sections, sectionId);
          if (!section) return undefined;

          const widget = section.widgets.find((w) => w.id === widgetId);
          if (!widget) return undefined;

          const newWidget: DashboardWidget = {
            ...widget,
            id: uniqid(),
            title: `${widget.title || widget.generatedTitle || ""} (copy)`,
          };

          const size = getLayoutItemSize(section.layout, widgetId);

          const updatedLayout = calculateLayoutForAddingWidget(
            section.layout,
            newWidget,
            size,
          );

          updateSectionById(
            sectionId,
            (s) => ({
              ...s,
              widgets: [...s.widgets, newWidget],
              layout: updatedLayout,
            }),
            "duplicateWidget",
          );

          return newWidget.id;
        },

        deleteWidget: (sectionId: string, widgetId: string) => {
          const state = get();
          const section = getSectionById(state.sections, sectionId);
          if (!section) return;

          const updatedWidgets = section.widgets.filter(
            (w) => w.id !== widgetId,
          );
          const updatedLayout = removeWidgetFromLayout(
            section.layout,
            widgetId,
          );

          updateSectionById(
            sectionId,
            (s) => ({
              ...s,
              widgets: updatedWidgets,
              layout: updatedLayout,
            }),
            "deleteWidget",
          );
        },

        updateWidget: (sectionId: string, widgetId: string, widgetConfig) => {
          const widgetResolver = get().widgetResolver;

          updateSectionById(
            sectionId,
            (s) => ({
              ...s,
              widgets: s.widgets.map((w) =>
                w.id === widgetId
                  ? updateWidgetWithGeneratedTitle(
                      w,
                      widgetConfig,
                      widgetResolver,
                    )
                  : w,
              ),
            }),
            "updateWidget",
          );
        },

        moveWidget: (
          sourceSectionId: string,
          targetSectionId: string,
          widgetId: string,
        ) => {
          const state = get();
          const sourceSection = getSectionById(state.sections, sourceSectionId);
          const targetSection = getSectionById(state.sections, targetSectionId);

          if (
            !sourceSection ||
            !targetSection ||
            sourceSectionId === targetSectionId
          ) {
            return;
          }

          const widget = sourceSection.widgets.find((w) => w.id === widgetId);
          if (!widget) return;

          const size = getLayoutItemSize(sourceSection.layout, widgetId);

          const updatedSourceWidgets = sourceSection.widgets.filter(
            (w) => w.id !== widgetId,
          );
          const updatedSourceLayout = removeWidgetFromLayout(
            sourceSection.layout,
            widgetId,
          );
          const updatedTargetLayout = calculateLayoutForAddingWidget(
            targetSection.layout,
            widget,
            size,
          );

          set(
            (state) => ({
              sections: state.sections.map((s) => {
                if (s.id === sourceSectionId) {
                  return {
                    ...s,
                    widgets: updatedSourceWidgets,
                    layout: updatedSourceLayout,
                  };
                }
                if (s.id === targetSectionId) {
                  return {
                    ...s,
                    widgets: [...s.widgets, widget],
                    layout: updatedTargetLayout,
                  };
                }
                return s;
              }),
              lastModified: Date.now(),
            }),
            false,
            "moveWidget",
          );
        },

        updateLayout: (sectionId: string, layout: DashboardLayout) => {
          updateSectionById(
            sectionId,
            (s) => ({ ...s, layout }),
            "updateLayout",
          );
        },

        setRuntimeConfig: (runtimeConfig) => {
          set({ runtimeConfig }, false, "setRuntimeConfig");
        },

        setOnAddEditWidgetCallback: (callback) => {
          set(
            { onAddEditWidgetCallback: callback },
            false,
            "setOnAddEditWidgetCallback",
          );
        },

        setWidgetResolver: (resolver) => {
          set({ widgetResolver: resolver }, false, "setWidgetResolver");
        },

        setPreviewWidget: (widget) => {
          set({ previewWidget: widget }, false, "setPreviewWidget");
        },

        updatePreviewWidget: (data) => {
          const currentPreview = get().previewWidget;
          if (!currentPreview) return;

          const widgetResolver = get().widgetResolver;
          const previewWidget = updateWidgetWithGeneratedTitle(
            currentPreview,
            data,
            widgetResolver,
          );

          set({ previewWidget }, false, "updatePreviewWidget");
        },

        setReadOnly: (readOnly) => {
          set({ readOnly }, false, "setReadOnly");
        },

        clearDashboard: () => {
          const emptyDashboard = generateEmptyDashboard();
          set(
            {
              sections: emptyDashboard.sections,
              version: emptyDashboard.version,
              lastModified: 0,
              readOnly: false,
            },
            false,
            "clearDashboard",
          );
        },

        loadDashboardFromBackend: (dashboardState: DashboardState) => {
          const migratedDashboard = migrateDashboardConfig(dashboardState);

          set(
            {
              sections: migratedDashboard.sections,
              version: migratedDashboard.version,
              lastModified: migratedDashboard.lastModified,
            },
            false,
            "loadDashboardFromBackend",
          );
        },

        getDashboard: () => {
          const state = get();
          return {
            sections: state.sections,
            version: state.version,
            lastModified: state.lastModified,
          };
        },

        getSectionById: (sectionId: string) => {
          const state = get();
          return getSectionById(state.sections, sectionId);
        },

        getWidgetById: (sectionId: string, widgetId: string) => {
          const state = get();
          const section = getSectionById(state.sections, sectionId);
          if (!section) return undefined;
          return section.widgets.find((w) => w.id === widgetId);
        },
      };
    },
    {
      name: "dashboard-store",
      enabled: process.env.NODE_ENV === "development",
    },
  ),
);

export const selectSectionIds = (state: DashboardStore) =>
  state.sections.map((s) => s.id);

export const selectSections = (state: DashboardStore) => state.sections;

export const selectAddSection = (state: DashboardStore) => state.addSection;
export const selectAddSectionAtPosition = (state: DashboardStore) =>
  state.addSectionAtPosition;
export const selectDeleteSection = (state: DashboardStore) =>
  state.deleteSection;
export const selectUpdateSection = (state: DashboardStore) =>
  state.updateSection;
export const selectReorderSections = (state: DashboardStore) =>
  state.reorderSections;

export const selectAddWidget = (state: DashboardStore) => state.addWidget;
export const selectDuplicateWidget = (state: DashboardStore) =>
  state.duplicateWidget;
export const selectDeleteWidget = (state: DashboardStore) => state.deleteWidget;
export const selectUpdateWidget = (state: DashboardStore) => state.updateWidget;
export const selectMoveWidget = (state: DashboardStore) => state.moveWidget;
export const selectUpdateLayout = (state: DashboardStore) => state.updateLayout;

export const selectRuntimeConfig = (state: DashboardStore) =>
  state.runtimeConfig;

export const selectSetRuntimeConfig = (state: DashboardStore) =>
  state.setRuntimeConfig;
export const selectClearDashboard = (state: DashboardStore) =>
  state.clearDashboard;

export const selectOnAddEditWidgetCallback = (state: DashboardStore) =>
  state.onAddEditWidgetCallback;
export const selectSetOnAddEditWidgetCallback = (state: DashboardStore) =>
  state.setOnAddEditWidgetCallback;

export const selectWidgetResolver = (state: DashboardStore) =>
  state.widgetResolver;
export const selectSetWidgetResolver = (state: DashboardStore) =>
  state.setWidgetResolver;

export const selectPreviewWidget = (state: DashboardStore) =>
  state.previewWidget;
export const selectSetPreviewWidget = (state: DashboardStore) =>
  state.setPreviewWidget;

export const selectUpdatePreviewWidget = (state: DashboardStore) =>
  state.updatePreviewWidget;

export const selectReadOnly = (state: DashboardStore) => state.readOnly;
export const selectSetReadOnly = (state: DashboardStore) => state.setReadOnly;
