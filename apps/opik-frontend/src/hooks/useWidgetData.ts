import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect } from 'react';
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

  const query = useQuery({
    queryKey: WIDGET_DATA_QUERY_KEYS.widget(widgetId),
    queryFn: () => dataService.fetchWidgetData<T>(dataSource, globalFilters),
    enabled: enabled && !!dataSource.endpoint,
    retry,
    staleTime,
    refetchInterval: refreshInterval * 1000,
    refetchOnWindowFocus: false,
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
      return queryClient.invalidateQueries({
        queryKey: WIDGET_DATA_QUERY_KEYS.widget(widgetId),
      });
    },
    [queryClient]
  );

  const refreshAllWidgets = useCallback(() => {
    return queryClient.invalidateQueries({
      queryKey: WIDGET_DATA_QUERY_KEYS.all,
    });
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
