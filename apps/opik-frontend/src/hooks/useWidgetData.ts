import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useRef } from 'react';
import dataService from '@/services/dataService';
import { DataSource, WidgetDataResponse } from '@/types/widget';

export const WIDGET_DATA_QUERY_KEYS = {
  all: ['widget-data'] as const,
  widget: (id: string) => [...WIDGET_DATA_QUERY_KEYS.all, id] as const,
};

interface UseWidgetDataOptions {
  enabled?: boolean;
  refreshInterval?: number; // in seconds
  retry?: number;
  staleTime?: number;
}

export function useWidgetData<T = any>(
  widgetId: string,
  dataSource: DataSource,
  globalFilters: Record<string, any> = {},
  options: UseWidgetDataOptions = {}
) {
  const {
    enabled = true,
    refreshInterval = 30,
    retry = 3,
    staleTime = 30 * 1000, // 30 seconds
  } = options;

  // Keep track of the previous endpoint to detect changes
  const prevEndpointRef = useRef<string>();
  
  // Only disable the query if there's truly no endpoint, not during configuration updates
  const hasValidEndpoint = !!dataSource.endpoint && dataSource.endpoint.trim() !== '';
  const isQueryEnabled = enabled && hasValidEndpoint;
  
  // Detect endpoint changes
  const endpointChanged = prevEndpointRef.current !== dataSource.endpoint;
  if (endpointChanged) {
    prevEndpointRef.current = dataSource.endpoint;
  }
  
  if (process.env.NODE_ENV === 'development') {
    console.log(`🟡 useWidgetData [${widgetId}] - Query enabled: ${isQueryEnabled}, endpoint: "${dataSource.endpoint}"`);
  }

  const query = useQuery({
    queryKey: WIDGET_DATA_QUERY_KEYS.widget(widgetId),
    queryFn: () => {
      if (process.env.NODE_ENV === 'development') {
        console.log(`🟡 useWidgetData [${widgetId}] - QueryFn executing for endpoint: ${dataSource.endpoint}`);
      }
      return dataService.fetchWidgetData<T>(dataSource, globalFilters);
    },
    enabled: isQueryEnabled,
    retry,
    staleTime,
    refetchInterval: refreshInterval * 1000,
    refetchOnWindowFocus: false,
    // Ensure we keep previous data while loading new data
    placeholderData: (previousData) => previousData,
  });

  // Keep a ref to store the last successful data to prevent flickering during refresh
  const lastSuccessfulDataRef = useRef<any>(null);
  
  // Update the ref when we get successful data
  useEffect(() => {
    if (query.data && !query.isPending && !query.error) {
      lastSuccessfulDataRef.current = query.data;
    }
  }, [query.data, query.isPending, query.error]);

  // During refresh, if query.data is undefined but we have cached data, use the cached data
  const effectiveData = query.data || (query.isPending && lastSuccessfulDataRef.current ? lastSuccessfulDataRef.current : []);
  
  if (process.env.NODE_ENV === 'development' && query.isPending && lastSuccessfulDataRef.current && !query.data) {
    console.log(`🟠 useWidgetData [${widgetId}] - Using cached data during refresh to prevent flicker`);
  }

  return {
    ...query,
    data: effectiveData,
    pagination: (effectiveData as any)?.pagination,
    metadata: (effectiveData as any)?.metadata,
    loading: query.isPending && !lastSuccessfulDataRef.current, // Don't show loading if we have cached data
    error: query.error ? query.error.message : null,
  };
}

export function useMockWidgetData(widgetId: string, widgetType: string) {
  const query = useQuery({
    queryKey: WIDGET_DATA_QUERY_KEYS.widget(widgetId),
    queryFn: () => {
      switch (widgetType) {
        case 'line_chart':
          return dataService.generateMockTimeSeriesData();
        case 'bar_chart':
          return dataService.generateMockCategoricalData();
        case 'pie_chart':
          return dataService.generateMockCategoricalData();
        case 'table':
          return dataService.generateMockTableData();
        case 'kpi_card':
          return dataService.generateMockKPIData();
        case 'heatmap':
          return dataService.generateMockHeatmapData();
        default:
          return { data: [] };
      }
    },
    staleTime: 60 * 1000, // 1 minute for mock data
    refetchInterval: 30 * 1000, // Refresh every 30 seconds for demo
  });

  return {
    ...query,
    data: query.data?.data || [],
    pagination: (query.data as any)?.pagination,
    metadata: (query.data as any)?.metadata,
    loading: query.isPending,
    error: query.error ? query.error.message : null,
  };
}

export function useWidgetDataManager() {
  const queryClient = useQueryClient();

  const refreshWidget = useCallback(
    (widgetId: string) => {
      console.log('🔄 refreshWidget - Starting refresh for widget:', widgetId);
      console.log('🔄 refreshWidget - Query key:', WIDGET_DATA_QUERY_KEYS.widget(widgetId));
      
      // Check current query state
      const existingQueries = queryClient.getQueryCache().getAll();
      const widgetQuery = existingQueries.find(q => 
        JSON.stringify(q.queryKey) === JSON.stringify(WIDGET_DATA_QUERY_KEYS.widget(widgetId))
      );
      
      console.log('🔄 refreshWidget - Existing query found:', !!widgetQuery);
      if (widgetQuery) {
        console.log('🔄 refreshWidget - Query state:', {
          state: widgetQuery.state.status,
          enabled: !widgetQuery.state.fetchStatus || widgetQuery.state.fetchStatus !== 'idle',
          data: !!widgetQuery.state.data
        });
      }
      
      // Force reset and re-enable the query
      queryClient.resetQueries({
        queryKey: WIDGET_DATA_QUERY_KEYS.widget(widgetId),
        exact: true,
      });
      
      // Immediately refetch to get new data
      const refetchResult = queryClient.refetchQueries({
        queryKey: WIDGET_DATA_QUERY_KEYS.widget(widgetId),
        exact: true,
      });
      
      console.log('🔄 refreshWidget - Refetch result:', refetchResult);
      
      return refetchResult;
    },
    [queryClient]
  );

  const refreshAllWidgets = useCallback(() => {
    if (process.env.NODE_ENV === 'development') {
      console.log('🟢 refreshAllWidgets - Starting refresh of all widgets');
    }
    
    const result = queryClient.invalidateQueries({
      queryKey: WIDGET_DATA_QUERY_KEYS.all,
    });
    
    // Also try to refetch all widget queries explicitly
    const allQueries = queryClient.getQueryCache().getAll();
    const widgetQueries = allQueries.filter(q => 
      q.queryKey[0] === 'widget-data' && q.queryKey.length > 1
    );
    
    widgetQueries.forEach(query => {
      queryClient.invalidateQueries({ queryKey: query.queryKey });
      // Also trigger explicit refetch for active queries only
      queryClient.refetchQueries({ 
        queryKey: query.queryKey,
        exact: true,
        type: 'active'
      });
    });
    
    if (process.env.NODE_ENV === 'development') {
      console.log('🟢 refreshAllWidgets - Complete, refreshed', widgetQueries.length, 'widgets');
    }
    
    return result;
  }, [queryClient]);

  const prefetchWidgetData = useCallback(
    (widgetId: string, dataSource: DataSource, globalFilters: Record<string, any> = {}) => {
      return queryClient.prefetchQuery({
        queryKey: WIDGET_DATA_QUERY_KEYS.widget(widgetId),
        queryFn: () => dataService.fetchWidgetData(dataSource, globalFilters),
        staleTime: 30 * 1000,
      });
    },
    [queryClient]
  );

  const cancelWidgetQuery = useCallback(
    (widgetId: string) => {
      return queryClient.cancelQueries({
        queryKey: WIDGET_DATA_QUERY_KEYS.widget(widgetId),
      });
    },
    [queryClient]
  );

  return {
    refreshWidget,
    refreshAllWidgets,
    prefetchWidgetData,
    cancelWidgetQuery,
  };
}

export function useAutoRefresh(enabled: boolean, interval: number, callback: () => void) {
  useEffect(() => {
    if (!enabled || interval <= 0) return;

    const intervalId = setInterval(callback, interval * 1000);
    return () => clearInterval(intervalId);
  }, [enabled, interval, callback]);
}
