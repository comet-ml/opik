import { create } from "zustand";
import { devtools } from "zustand/middleware";
import { arrayMove } from "@dnd-kit/sortable";
import { UniqueIdentifier } from "@dnd-kit/core";
import uniqid from "uniqid";

import {
  BaseDashboardConfig,
  DashboardLayout,
  DashboardSection,
  DashboardSections,
  DashboardState,
  DashboardWidget,
  AddWidgetConfig,
  UpdateWidgetConfig,
  WidgetResolver,
  AddEditWidgetCallbackParams,
} from "@/types/dashboard";
import {
  generateEmptyDashboard,
  generateEmptySection,
  generateEmptyConfig,
  getSectionById,
} from "@/lib/dashboard/utils";
import {
  calculateLayoutForAddingWidget,
  removeWidgetFromLayout,
} from "@/lib/dashboard/layout";
import { migrateDashboardConfig } from "@/lib/dashboard/migrations";

/**
 * Dashboard Store State
 */
interface DashboardStoreState<TConfig = BaseDashboardConfig> {
  sections: DashboardSections;
  version: number;
  lastModified: number;
  config: TConfig | null;
  runtimeConfig: Partial<TConfig>;
  onAddEditWidgetCallback:
    | ((params: AddEditWidgetCallbackParams) => void)
    | null;
  widgetResolver: WidgetResolver | null;
  previewWidget: DashboardWidget | null;
  hasUnsavedChanges: boolean;
}

/**
 * Dashboard Store Actions
 */
interface DashboardActions<TConfig = BaseDashboardConfig> {
  // Section operations
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

  // Widget operations
  addWidget: (sectionId: string, config: AddWidgetConfig) => string | undefined;
  duplicateWidget: (sectionId: string, widgetId: string) => string | undefined;
  deleteWidget: (sectionId: string, widgetId: string) => void;
  updateWidget: (
    sectionId: string,
    widgetId: string,
    config: UpdateWidgetConfig,
  ) => void;
  moveWidget: (
    sourceSectionId: string,
    targetSectionId: string,
    widgetId: string,
  ) => void;
  updateLayout: (sectionId: string, layout: DashboardLayout) => void;

  // Config operations
  setConfig: (config: TConfig) => void;
  setRuntimeConfig: (runtimeConfig: Partial<TConfig>) => void;

  // Callback operations
  setOnAddEditWidgetCallback: (
    callback: ((params: AddEditWidgetCallbackParams) => void) | null,
  ) => void;

  // Widget resolver operations
  setWidgetResolver: (resolver: WidgetResolver | null) => void;

  // Preview widget operations
  setPreviewWidget: (widget: DashboardWidget | null) => void;

  // Unsaved changes operations
  setHasUnsavedChanges: (hasChanges: boolean) => void;

  // Utility operations
  clearDashboard: () => void;

  // Backend sync operations
  loadDashboardFromBackend: (dashboardState: DashboardState) => void;
  getDashboard: () => DashboardState;

  // Getters (computed values)
  getSectionById: (sectionId: string) => DashboardSection | undefined;
  getWidgetById: (
    sectionId: string,
    widgetId: string,
  ) => DashboardWidget | undefined;
}

/**
 * Combined Dashboard Store Type
 */
export type DashboardStore<TConfig = BaseDashboardConfig> =
  DashboardStoreState<TConfig> & DashboardActions<TConfig>;

/**
 * Dashboard Store with Zustand
 *
 * Key features:
 * - Devtools middleware for debugging
 * - Stable action references (no recreating functions)
 * - Centralized state management
 * - Normalized state updates
 */
export const useDashboardStore = create<DashboardStore<BaseDashboardConfig>>()(
  devtools(
    (set, get) => {
      const initialDashboard = generateEmptyDashboard();

      return {
        // State
        sections: initialDashboard.sections,
        version: initialDashboard.version,
        lastModified: initialDashboard.lastModified,
        config: initialDashboard.config,
        runtimeConfig: {},
        onAddEditWidgetCallback: null,
        widgetResolver: null,
        previewWidget: null,
        hasUnsavedChanges: false,

        // Section Actions
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
          set(
            (state) => ({
              sections: state.sections.map((section) =>
                section.id === sectionId ? { ...section, ...updates } : section,
              ),
              lastModified: Date.now(),
            }),
            false,
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

        // Widget Actions
        addWidget: (sectionId: string, widgetConfig) => {
          const state = get();
          const section = getSectionById(state.sections, sectionId);
          if (!section) return undefined;

          const newWidget: DashboardWidget = {
            id: uniqid(),
            type: widgetConfig.type,
            title: widgetConfig.title,
            subtitle: widgetConfig.subtitle,
            config: widgetConfig.config || {},
          };

          const updatedLayout = calculateLayoutForAddingWidget(
            section.layout,
            newWidget,
          );

          set(
            (state) => ({
              sections: state.sections.map((s) =>
                s.id === sectionId
                  ? {
                      ...s,
                      widgets: [...s.widgets, newWidget],
                      layout: updatedLayout,
                    }
                  : s,
              ),
              lastModified: Date.now(),
            }),
            false,
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

          const layoutItem = section.layout.find((item) => item.i === widgetId);

          const newWidget: DashboardWidget = {
            id: uniqid(),
            type: widget.type,
            title: `${widget.title} (copy)`,
            subtitle: widget.subtitle,
            config: widget.config || {},
          };

          const size = layoutItem
            ? { w: layoutItem.w, h: layoutItem.h }
            : undefined;

          const updatedLayout = calculateLayoutForAddingWidget(
            section.layout,
            newWidget,
            size,
          );

          set(
            (state) => ({
              sections: state.sections.map((s) =>
                s.id === sectionId
                  ? {
                      ...s,
                      widgets: [...s.widgets, newWidget],
                      layout: updatedLayout,
                    }
                  : s,
              ),
              lastModified: Date.now(),
            }),
            false,
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

          set(
            (state) => ({
              sections: state.sections.map((s) =>
                s.id === sectionId
                  ? {
                      ...s,
                      widgets: updatedWidgets,
                      layout: updatedLayout,
                    }
                  : s,
              ),
              lastModified: Date.now(),
            }),
            false,
            "deleteWidget",
          );
        },

        updateWidget: (sectionId: string, widgetId: string, widgetConfig) => {
          set(
            (state) => ({
              sections: state.sections.map((s) =>
                s.id === sectionId
                  ? {
                      ...s,
                      widgets: s.widgets.map((w) =>
                        w.id === widgetId
                          ? {
                              ...w,
                              ...(widgetConfig.title !== undefined && {
                                title: widgetConfig.title,
                              }),
                              ...(widgetConfig.subtitle !== undefined && {
                                subtitle: widgetConfig.subtitle,
                              }),
                              ...(widgetConfig.config !== undefined && {
                                config: { ...w.config, ...widgetConfig.config },
                              }),
                            }
                          : w,
                      ),
                    }
                  : s,
              ),
              lastModified: Date.now(),
            }),
            false,
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

          const layoutItem = sourceSection.layout.find(
            (item) => item.i === widgetId,
          );
          const size = layoutItem
            ? { w: layoutItem.w, h: layoutItem.h }
            : undefined;

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
          set(
            (state) => ({
              sections: state.sections.map((s) =>
                s.id === sectionId ? { ...s, layout } : s,
              ),
              lastModified: Date.now(),
            }),
            false,
            "updateLayout",
          );
        },

        // Config Actions
        setConfig: (config) => {
          set({ config }, false, "setConfig");
        },

        setRuntimeConfig: (runtimeConfig) => {
          set({ runtimeConfig }, false, "setRuntimeConfig");
        },

        // Callback Actions
        setOnAddEditWidgetCallback: (callback) => {
          set(
            { onAddEditWidgetCallback: callback },
            false,
            "setOnAddEditWidgetCallback",
          );
        },

        // Widget Resolver Actions
        setWidgetResolver: (resolver) => {
          set({ widgetResolver: resolver }, false, "setWidgetResolver");
        },

        // Preview Widget Actions
        setPreviewWidget: (widget) => {
          set({ previewWidget: widget }, false, "setPreviewWidget");
        },

        // Unsaved Changes Actions
        setHasUnsavedChanges: (hasChanges) => {
          set({ hasUnsavedChanges: hasChanges }, false, "setHasUnsavedChanges");
        },

        // Utility Actions
        clearDashboard: () => {
          const emptyDashboard = generateEmptyDashboard();
          set(
            {
              sections: emptyDashboard.sections,
              version: emptyDashboard.version,
              lastModified: Date.now(),
              config: emptyDashboard.config,
            },
            false,
            "clearDashboard",
          );
        },

        // Backend Sync Actions
        loadDashboardFromBackend: (dashboardState: DashboardState) => {
          const migratedDashboard = migrateDashboardConfig(dashboardState);
          set(
            {
              sections: migratedDashboard.sections,
              version: migratedDashboard.version,
              lastModified: migratedDashboard.lastModified,
              config: migratedDashboard.config,
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
            config: state.config ?? generateEmptyConfig(),
          };
        },

        // Getters
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

/**
 * Selective Selectors for Performance Optimization
 *
 * These selectors allow components to subscribe only to specific parts
 * of the store, preventing unnecessary re-renders.
 *
 * Usage with useShallow:
 * const sectionIds = useDashboardStore(useShallow(state => selectSectionIds(state)));
 */

export const selectSectionIds = (state: DashboardStore) =>
  state.sections.map((s) => s.id);

export const selectSections = (state: DashboardStore) => state.sections;

/**
 * Action Selectors (stable references)
 *
 * These selectors return action functions that never change,
 * preventing unnecessary re-renders in child components.
 */

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

export const selectMixedConfig = <TConfig = BaseDashboardConfig>(
  state: DashboardStore<TConfig>,
) => {
  if (!state.config) return null;
  return {
    ...state.config,
    ...state.runtimeConfig,
  } as TConfig;
};

export const selectConfig = <TConfig = BaseDashboardConfig>(
  state: DashboardStore<TConfig>,
) => state.config;

export const selectSetConfig = (state: DashboardStore<BaseDashboardConfig>) =>
  state.setConfig;

export const selectSetRuntimeConfig = (
  state: DashboardStore<BaseDashboardConfig>,
) => state.setRuntimeConfig;
export const selectClearDashboard = (
  state: DashboardStore<BaseDashboardConfig>,
) => state.clearDashboard;

// Callback selectors
export const selectOnAddEditWidgetCallback = (
  state: DashboardStore<BaseDashboardConfig>,
) => state.onAddEditWidgetCallback;
export const selectSetOnAddEditWidgetCallback = (
  state: DashboardStore<BaseDashboardConfig>,
) => state.setOnAddEditWidgetCallback;

// Widget resolver selectors
export const selectWidgetResolver = (
  state: DashboardStore<BaseDashboardConfig>,
) => state.widgetResolver;
export const selectSetWidgetResolver = (
  state: DashboardStore<BaseDashboardConfig>,
) => state.setWidgetResolver;

// Preview widget selectors
export const selectPreviewWidget = (
  state: DashboardStore<BaseDashboardConfig>,
) => state.previewWidget;
export const selectSetPreviewWidget = (
  state: DashboardStore<BaseDashboardConfig>,
) => state.setPreviewWidget;

// Unsaved changes selectors
export const selectHasUnsavedChanges = (
  state: DashboardStore<BaseDashboardConfig>,
) => state.hasUnsavedChanges;
export const selectSetHasUnsavedChanges = (
  state: DashboardStore<BaseDashboardConfig>,
) => state.setHasUnsavedChanges;
