import apiClient from './api';
import { DataSource, WidgetDataResponse } from '@/types/widget';

export class DataService {
  async fetchWidgetData<T = any>(
    dataSource: DataSource,
    globalFilters: Record<string, any> = {}
  ): Promise<WidgetDataResponse<T>> {
    const { endpoint, method, headers, queryParams, transformData } = dataSource;
    
    // Merge query params with global filters
    const params = { ...queryParams, ...globalFilters };

    try {
      let response;
      
      if (method === 'POST') {
        response = await apiClient.post<WidgetDataResponse<T>>(endpoint, params, { headers });
      } else {
        response = await apiClient.get<WidgetDataResponse<T>>(endpoint, { 
          params, 
          headers 
        });
      }

      // Apply data transformation if provided
      if (transformData && response.data) {
        response.data.data = transformData(response.data.data);
      }

      return response.data;
    } catch (error) {
      console.error('Error fetching widget data:', error);
      throw error;
    }
  }

  // Mock data generation for development
  generateMockTimeSeriesData(points: number = 30) {
    const data = [];
    const now = new Date();
    
    for (let i = points - 1; i >= 0; i--) {
      const timestamp = new Date(now.getTime() - i * 60 * 60 * 1000).toISOString();
      data.push({
        timestamp,
        value: Math.floor(Math.random() * 1000) + 500,
        series: 'requests'
      });
    }
    
    return { data };
  }

  generateMockCategoricalData() {
    return {
      data: [
        { category: 'success', count: 850 },
        { category: 'error', count: 45 },
        { category: 'timeout', count: 12 },
        { category: 'cancelled', count: 8 }
      ]
    };
  }

  generateMockTableData() {
    return {
      data: Array.from({ length: 10 }, (_, i) => ({
        id: i + 1,
        name: `Trace ${i + 1}`,
        duration: Math.floor(Math.random() * 1000) + 100,
        status: Math.random() > 0.8 ? 'error' : 'success',
        timestamp: new Date(Date.now() - Math.random() * 86400000).toISOString()
      })),
      pagination: { page: 1, totalPages: 5, totalItems: 50 }
    };
  }

  generateMockKPIData() {
    return {
      data: {
        value: 98.5,
        label: 'Success Rate',
        trend: {
          direction: 'up' as const,
          percentage: 2.3
        },
        format: 'percentage' as const
      }
    };
  }

  generateMockHeatmapData() {
    const data = [];
    const hours = ['00', '06', '12', '18'];
    const days = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
    
    for (let i = 0; i < hours.length; i++) {
      for (let j = 0; j < days.length; j++) {
        data.push({
          x: hours[i],
          y: days[j],
          value: Math.floor(Math.random() * 100)
        });
      }
    }
    
    return { data };
  }
}

export default new DataService();
