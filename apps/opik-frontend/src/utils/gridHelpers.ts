import { Layout } from 'react-grid-layout';
import { DashboardLayout, WidgetType } from '@/types/dashboard';

export const defaultWidgetSizes: Record<WidgetType, { w: number; h: number }> = {
  line_chart: { w: 6, h: 4 },
  bar_chart: { w: 6, h: 4 },
  pie_chart: { w: 4, h: 4 },
  table: { w: 8, h: 6 },
  kpi_card: { w: 3, h: 2 },
  heatmap: { w: 8, h: 5 },
};

export const minWidgetSizes: Record<WidgetType, { w: number; h: number }> = {
  line_chart: { w: 4, h: 3 },
  bar_chart: { w: 4, h: 3 },
  pie_chart: { w: 3, h: 3 },
  table: { w: 6, h: 4 },
  kpi_card: { w: 2, h: 2 },
  heatmap: { w: 6, h: 4 },
};

export const maxWidgetSizes: Record<WidgetType, { w: number; h: number }> = {
  line_chart: { w: 12, h: 8 },
  bar_chart: { w: 12, h: 8 },
  pie_chart: { w: 8, h: 8 },
  table: { w: 12, h: 10 },
  kpi_card: { w: 6, h: 4 },
  heatmap: { w: 12, h: 8 },
};

export function convertToReactGridLayout(dashboardLayout: DashboardLayout[]): Layout[] {
  return dashboardLayout.map(item => ({
    i: item.id,
    x: item.x,
    y: item.y,
    w: item.w,
    h: item.h,
    minW: minWidgetSizes[item.type].w,
    minH: minWidgetSizes[item.type].h,
    maxW: maxWidgetSizes[item.type].w,
    maxH: maxWidgetSizes[item.type].h,
  }));
}

export function convertFromReactGridLayout(
  reactGridLayout: Layout[],
  dashboardLayout: DashboardLayout[]
): DashboardLayout[] {
  return dashboardLayout.map(item => {
    const gridItem = reactGridLayout.find(gl => gl.i === item.id);
    if (!gridItem) return item;
    
    return {
      ...item,
      x: gridItem.x,
      y: gridItem.y,
      w: gridItem.w,
      h: gridItem.h,
    };
  });
}

export function findOptimalPosition(
  existingLayouts: DashboardLayout[],
  widgetType: WidgetType,
  gridCols: number = 12
): { x: number; y: number } {
  const widgetSize = defaultWidgetSizes[widgetType];
  const existingGridItems = convertToReactGridLayout(existingLayouts);
  
  // Find the next available position
  let y = 0;
  let x = 0;
  
  while (true) {
    let collision = false;
    
    // Check if current position collides with existing widgets
    for (const item of existingGridItems) {
      if (
        x < item.x + item.w &&
        x + widgetSize.w > item.x &&
        y < item.y + item.h &&
        y + widgetSize.h > item.y
      ) {
        collision = true;
        break;
      }
    }
    
    if (!collision && x + widgetSize.w <= gridCols) {
      return { x, y };
    }
    
    // Move to next position
    x += 1;
    if (x + widgetSize.w > gridCols) {
      x = 0;
      y += 1;
    }
  }
}

export function generateUniqueWidgetId(): string {
  return `widget_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

export function compactLayout(layout: DashboardLayout[]): DashboardLayout[] {
  // Sort by y position, then by x position
  const sorted = [...layout].sort((a, b) => {
    if (a.y === b.y) return a.x - b.x;
    return a.y - b.y;
  });
  
  const compacted: DashboardLayout[] = [];
  
  for (const item of sorted) {
    let newY = 0;
    
    // Find the highest point this widget can be placed
    for (const existing of compacted) {
      if (
        item.x < existing.x + existing.w &&
        item.x + item.w > existing.x
      ) {
        newY = Math.max(newY, existing.y + existing.h);
      }
    }
    
    compacted.push({ ...item, y: newY });
  }
  
  return compacted;
}

export function validateLayout(layout: DashboardLayout[]): boolean {
  for (let i = 0; i < layout.length; i++) {
    for (let j = i + 1; j < layout.length; j++) {
      const a = layout[i];
      const b = layout[j];
      
      // Check for overlaps
      if (
        a.x < b.x + b.w &&
        a.x + a.w > b.x &&
        a.y < b.y + b.h &&
        a.y + a.h > b.y
      ) {
        return false;
      }
    }
  }
  return true;
}
