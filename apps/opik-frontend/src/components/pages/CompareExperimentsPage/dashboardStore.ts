import { create } from 'zustand';
import { PanelSectionLayout } from './dashboardTypes';

// Backend API Integration Store
export type DashboardStore = {
  // Current State
  currentDashboardId: string | null;
  currentExperimentId: string | null;
  
  // Actions
  setCurrentDashboard: (dashboardId: string | null) => void;
  setCurrentExperiment: (experimentId: string | null) => void;
  
  // Helper methods for panel operations (these will be used by components)
  // The actual API calls will be made by the components using the API hooks
  calculateNextPosition: (layout: PanelSectionLayout, defaultWidth?: number, defaultHeight?: number) => { x: number; y: number; w: number; h: number };
};

// Helper function to calculate the next available position for a new panel
const calculateNextPosition = (layout: PanelSectionLayout, defaultWidth = 6, defaultHeight = 1) => {
  const DASHBOARD_COLUMNS = 12;
  
  for (let y = 0; ; y++) {
    for (let x = 0; x <= DASHBOARD_COLUMNS - defaultWidth; x++) {
      const overlaps = layout.some(item => {
        return (
          x < item.x + item.w &&
          x + defaultWidth > item.x &&
          y < item.y + item.h &&
          y + defaultHeight > item.y
        );
      });

      if (!overlaps) {
        return { x, y, w: defaultWidth, h: defaultHeight };
      }
    }
  }
};

export const useDashboardStore = create<DashboardStore>((set, get) => ({
  // Initial State
  currentDashboardId: null,
  currentExperimentId: null,
  
  // Actions
  setCurrentDashboard: (dashboardId) => set({ currentDashboardId: dashboardId }),
  setCurrentExperiment: (experimentId) => set({ currentExperimentId: experimentId }),
  
  // Helper methods
  calculateNextPosition,
})); 