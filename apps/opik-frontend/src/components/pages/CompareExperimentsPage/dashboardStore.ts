import { create } from 'zustand';
import { PanelSectionLayout } from './dashboardTypes';

// Simplified Dashboard Store - Only for UI state and calculations
export type DashboardStore = {
  // Current UI State (minimal, most state should be in React Query)
  currentDashboardId: string | null;
  
  // Actions
  setCurrentDashboard: (dashboardId: string | null) => void;
  
  // Helper methods for panel layout calculations
  calculateNextPosition: (layout: PanelSectionLayout, defaultWidth?: number, defaultHeight?: number) => { x: number; y: number; w: number; h: number };
};

export const useDashboardStore = create<DashboardStore>((set, get) => ({
  // Initial state
  currentDashboardId: null,
  
  // Actions
  setCurrentDashboard: (dashboardId) => set({ currentDashboardId: dashboardId }),
  
  // Layout calculations
  calculateNextPosition: (layout, defaultWidth = 6, defaultHeight = 2) => {
    if (!layout || layout.length === 0) {
      return { x: 0, y: 0, w: defaultWidth, h: defaultHeight };
    }

    // Find the next available position
    const maxY = Math.max(...layout.map(item => item.y + item.h));
    let x = 0;
    let y = maxY;
    
    // Try to find a spot in existing rows first
    for (let currentY = 0; currentY <= maxY; currentY++) {
      const rowItems = layout.filter(item => 
        item.y <= currentY && item.y + item.h > currentY
      );
      
      if (rowItems.length === 0) {
        x = 0;
        y = currentY;
        break;
      }
      
      // Check for gaps in this row
      rowItems.sort((a, b) => a.x - b.x);
      let currentX = 0;
      
      for (const item of rowItems) {
        if (currentX + defaultWidth <= item.x) {
          x = currentX;
          y = currentY;
          return { x, y, w: defaultWidth, h: defaultHeight };
        }
        currentX = item.x + item.w;
      }
      
      // Check if we can fit at the end of this row
      if (currentX + defaultWidth <= 12) { // Assuming 12 columns
        x = currentX;
        y = currentY;
        return { x, y, w: defaultWidth, h: defaultHeight };
      }
    }
    
    return { x, y, w: defaultWidth, h: defaultHeight };
  },
})); 
