import { useState, useCallback, useRef } from 'react';
import { Layout } from 'react-grid-layout';
import { DashboardLayout, WidgetType } from '@/types/dashboard';
import {
  convertToReactGridLayout,
  convertFromReactGridLayout,
  findOptimalPosition,
  generateUniqueWidgetId,
  defaultWidgetSizes,
  compactLayout,
} from '@/utils/gridHelpers';

interface UseGridLayoutOptions {
  initialLayout?: DashboardLayout[];
  cols?: number;
  rowHeight?: number;
  autoCompact?: boolean;
  onLayoutChange?: (layout: DashboardLayout[]) => void;
}

export function useGridLayout(options: UseGridLayoutOptions = {}) {
  const {
    initialLayout = [],
    cols = 12,
    rowHeight = 60,
    autoCompact = true,
    onLayoutChange,
  } = options;

  const [dashboardLayout, setDashboardLayout] = useState<DashboardLayout[]>(initialLayout);
  const [isDragging, setIsDragging] = useState(false);
  const [isResizing, setIsResizing] = useState(false);
  const layoutRef = useRef<DashboardLayout[]>(dashboardLayout);

  // Update ref when layout changes
  layoutRef.current = dashboardLayout;

  const reactGridLayout = convertToReactGridLayout(dashboardLayout);

  const handleLayoutChange = useCallback(
    (newLayout: Layout[]) => {
      const updatedLayout = convertFromReactGridLayout(newLayout, layoutRef.current);
      const finalLayout = autoCompact ? compactLayout(updatedLayout) : updatedLayout;
      
      setDashboardLayout(finalLayout);
      onLayoutChange?.(finalLayout);
    },
    [autoCompact, onLayoutChange]
  );

  const addWidget = useCallback(
    (widgetType: WidgetType, config?: Partial<DashboardLayout['config']>) => {
      const id = generateUniqueWidgetId();
      const position = findOptimalPosition(layoutRef.current, widgetType, cols);
      const size = defaultWidgetSizes[widgetType];

      const newWidget: DashboardLayout = {
        id,
        ...position,
        ...size,
        type: widgetType,
        config: {
          title: `New ${widgetType.replace('_', ' ')}`,
          dataSource: '',
          queryParams: {},
          chartOptions: {},
          ...config,
        },
      };

      const newLayout = [...layoutRef.current, newWidget];
      const finalLayout = autoCompact ? compactLayout(newLayout) : newLayout;
      
      setDashboardLayout(finalLayout);
      onLayoutChange?.(finalLayout);
      
      return id;
    },
    [cols, autoCompact, onLayoutChange]
  );

  const removeWidget = useCallback(
    (widgetId: string) => {
      const newLayout = layoutRef.current.filter(widget => widget.id !== widgetId);
      const finalLayout = autoCompact ? compactLayout(newLayout) : newLayout;
      
      setDashboardLayout(finalLayout);
      onLayoutChange?.(finalLayout);
    },
    [autoCompact, onLayoutChange]
  );

  const updateWidget = useCallback(
    (widgetId: string, updates: Partial<DashboardLayout>) => {
      const newLayout = layoutRef.current.map(widget =>
        widget.id === widgetId ? { ...widget, ...updates } : widget
      );
      
      setDashboardLayout(newLayout);
      onLayoutChange?.(newLayout);
    },
    [onLayoutChange]
  );

  const duplicateWidget = useCallback(
    (widgetId: string) => {
      const originalWidget = layoutRef.current.find(w => w.id === widgetId);
      if (!originalWidget) return;

      const id = generateUniqueWidgetId();
      const position = findOptimalPosition(layoutRef.current, originalWidget.type, cols);

      const duplicatedWidget: DashboardLayout = {
        ...originalWidget,
        id,
        ...position,
        config: {
          ...originalWidget.config,
          title: `${originalWidget.config.title} (Copy)`,
        },
      };

      const newLayout = [...layoutRef.current, duplicatedWidget];
      const finalLayout = autoCompact ? compactLayout(newLayout) : newLayout;
      
      setDashboardLayout(finalLayout);
      onLayoutChange?.(finalLayout);
      
      return id;
    },
    [cols, autoCompact, onLayoutChange]
  );

  const clearLayout = useCallback(() => {
    setDashboardLayout([]);
    onLayoutChange?.([]);
  }, [onLayoutChange]);

  const resetLayout = useCallback(() => {
    setDashboardLayout(initialLayout);
    onLayoutChange?.(initialLayout);
  }, [initialLayout, onLayoutChange]);

  const compactLayoutManually = useCallback(() => {
    const compactedLayout = compactLayout(layoutRef.current);
    setDashboardLayout(compactedLayout);
    onLayoutChange?.(compactedLayout);
  }, [onLayoutChange]);

  const getWidget = useCallback(
    (widgetId: string) => {
      return layoutRef.current.find(widget => widget.id === widgetId);
    },
    []
  );

  // Grid event handlers
  const handleDragStart = useCallback(() => {
    setIsDragging(true);
  }, []);

  const handleDragStop = useCallback(() => {
    setIsDragging(false);
  }, []);

  const handleResizeStart = useCallback(() => {
    setIsResizing(true);
  }, []);

  const handleResizeStop = useCallback(() => {
    setIsResizing(false);
  }, []);

  return {
    // Layout state
    dashboardLayout,
    reactGridLayout,
    isDragging,
    isResizing,
    
    // Layout management
    addWidget,
    removeWidget,
    updateWidget,
    duplicateWidget,
    clearLayout,
    resetLayout,
    compactLayoutManually,
    getWidget,
    
    // Grid configuration
    cols,
    rowHeight,
    
    // Event handlers
    onLayoutChange: handleLayoutChange,
    onDragStart: handleDragStart,
    onDragStop: handleDragStop,
    onResizeStart: handleResizeStart,
    onResizeStop: handleResizeStop,
  };
}

export function useGridBreakpoints() {
  const [currentBreakpoint, setCurrentBreakpoint] = useState<string>('lg');
  
  const breakpoints = {
    lg: 1200,
    md: 996,
    sm: 768,
    xs: 480,
    xxs: 0,
  };
  
  const cols = {
    lg: 12,
    md: 10,
    sm: 6,
    xs: 4,
    xxs: 2,
  };

  const handleBreakpointChange = useCallback((breakpoint: string) => {
    setCurrentBreakpoint(breakpoint);
  }, []);

  return {
    currentBreakpoint,
    breakpoints,
    cols,
    onBreakpointChange: handleBreakpointChange,
  };
}
