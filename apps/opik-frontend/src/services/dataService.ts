import apiClient from './api';
import axios from 'axios';
import { DataSource, WidgetDataResponse } from '@/types/widget';

export interface ApiTestResult {
  success: boolean;
  message: string;
  data?: any;
  error?: string;
}

export interface FieldInfo {
  name: string;
  path: string;
  type: string;
  sampleValue?: any;
}

export class DataService {
  /**
   * Check if URL is a full URL (starts with http:// or https://)
   */
  private isFullUrl(url: string): boolean {
    return url.startsWith('http://') || url.startsWith('https://');
  }

  /**
   * Create axios instance for external URLs
   */
  private createExternalClient() {
    return axios.create({
      timeout: 30000,
      headers: {
        'Content-Type': 'application/json',
      },
    });
  }

  /**
   * Extract value from nested object using dot notation path
   * Supports array notation like "content[].field" or "[].field" for root arrays
   */
  extractValueByPath(obj: any, path: string): any[] {
    if (!path || !obj) {
      return [];
    }

    const parts = path.split('.');
    let current = obj;
    
    for (const part of parts) {
      if (part.endsWith('[]')) {
        // Handle array notation like "content[]" or "[]"
        const arrayKey = part.slice(0, -2);
        
        if (arrayKey === '') {
          // Handle root array case: "[].field"
          if (!Array.isArray(current)) {
            return [];
          }
        } else {
          // Handle nested array case: "content[].field"
          current = current[arrayKey];
          if (!Array.isArray(current)) {
            return [];
          }
        }
        
        // Process remaining path parts on array items
        const remainingParts = parts.slice(parts.indexOf(part) + 1);
        
        if (remainingParts.length === 0) {
          // No more parts, return the array itself
          return current;
        } else {
          // Extract field from each array item
          const remainingPath = remainingParts.join('.');
          const results = current.map(item => {
            const itemResult = this.extractValueByPath(item, remainingPath);
            return itemResult[0]; // Take first result from recursive call
          }).filter(val => val !== undefined);
          return results;
        }
      } else if (part === 'length') {
        // Handle array length
        const result = Array.isArray(current) ? current.length : 0;
        return [result];
      } else {
        // Regular property access
        current = current[part];
        if (current === undefined || current === null) {
          return [];
        }
      }
    }

    // If we reach here, we have a single value
    return [current];
  }

  /**
   * Transform API response data into chart format using chart options
   */
  transformDataForChart(rawData: any, chartOptions: any, widgetType: string): any {
    if (!rawData || !chartOptions) {
      return [];
    }

    const { xAxisKey, yAxisKey } = chartOptions;
    
    if (!xAxisKey || !yAxisKey) {
      return [];
    }

    try {
      // Extract values using the configured paths
      const xValues = this.extractValueByPath(rawData, xAxisKey);
      const yValues = this.extractValueByPath(rawData, yAxisKey);

      if (xValues.length === 0 && yValues.length === 0) {
        return [];
      }

      // Handle case where we have arrays of different lengths
      const maxLength = Math.max(xValues.length, yValues.length);
      const data = [];

      for (let i = 0; i < maxLength; i++) {
        const xValue = xValues[i] !== undefined ? xValues[i] : xValues[0];
        const yValue = yValues[i] !== undefined ? yValues[i] : yValues[0];

        if (xValue !== undefined && yValue !== undefined) {
          switch (widgetType) {
            case 'line_chart':
              data.push({ 
                timestamp: xValue, 
                series: yAxisKey.split('.').pop() || 'value', 
                value: Number(yValue) || 0 
              });
              break;
            case 'bar_chart':
            case 'pie_chart':
              data.push({ 
                category: String(xValue), 
                count: Number(yValue) || 0 
              });
              break;
            case 'table':
              return Array.isArray(rawData.content) ? rawData.content : [rawData];
            default:
              data.push({ x: xValue, y: yValue });
          }
        }
      }

      return data;
    } catch (error) {
      console.error('Error in transformDataForChart:', error);
      return [];
    }
  }

  async fetchWidgetData<T = any>(
    dataSource: DataSource,
    globalFilters: Record<string, any> = {}
  ): Promise<any> {
    const { endpoint, method, headers, queryParams, transformData } = dataSource;
    
    // Merge query params with global filters
    const params = { ...queryParams, ...globalFilters };

    try {
      let responseData;
      
      // Check if endpoint is a full URL (and trim whitespace)
      const trimmedEndpoint = endpoint.trim();
      if (this.isFullUrl(trimmedEndpoint)) {
        
        // Use external axios client for full URLs
        const externalClient = this.createExternalClient();
        
        if (method === 'POST') {
          const response = await externalClient.post<T>(trimmedEndpoint, params, { headers });
          responseData = response.data;
        } else {
          const response = await externalClient.get<T>(trimmedEndpoint, { 
            params, 
            headers 
          });
          responseData = response.data;
        }
      } else {
        
        // Use internal API client for relative URLs
        if (method === 'POST') {
          responseData = await apiClient.post<T>(trimmedEndpoint, params, { headers });
        } else {
          const response = await apiClient.get<T>(trimmedEndpoint, { params, headers });
          responseData = response.data;
        }
      }

      // Apply data transformation if provided
      const finalData = transformData ? transformData(responseData) : responseData;

      return finalData;
    } catch (error) {
      console.error('Error fetching widget data:', error);
      throw error;
    }
  }

  /**
   * Test API connection and return sample data
   */
  async testApiConnection(dataSource: DataSource, globalFilters: Record<string, any> = {}): Promise<ApiTestResult> {
    if (!dataSource.endpoint) {
      return {
        success: false,
        message: 'API endpoint is required',
        error: 'MISSING_ENDPOINT'
      };
    }

    try {
      const response = await this.fetchWidgetData(dataSource, globalFilters);
      return {
        success: true,
        message: 'Connection successful',
        data: response
      };
    } catch (error: any) {
      console.error('API connection test failed:', error);
      return {
        success: false,
        message: error.message || 'Connection failed',
        error: error.code || 'CONNECTION_ERROR'
      };
    }
  }

  /**
   * Extract available fields from API response data
   */
  extractFieldsFromData(data: any): FieldInfo[] {
    const fields: FieldInfo[] = [];
    
    const traverse = (obj: any, path: string = '', parentKey: string = '') => {
      if (obj === null || obj === undefined) {
        return;
      }

      if (Array.isArray(obj)) {
        // For arrays, analyze the first item if it exists
        if (obj.length > 0) {
          // Check if this is the root array
          const arrayPath = path === '' ? '[]' : `${path}[]`;
          traverse(obj[0], arrayPath, parentKey);
        }
        // Also add the array length as a field
        const lengthPath = path === '' ? 'length' : `${path}.length`;
        fields.push({
          name: `${parentKey || 'Root'} (count)`,
          path: lengthPath,
          type: 'number',
          sampleValue: obj.length
        });
      } else if (typeof obj === 'object') {
        Object.keys(obj).forEach(key => {
          const value = obj[key];
          const newPath = path ? `${path}.${key}` : key;
          const fieldName = key.replace(/_/g, ' ').replace(/\b\w/g, l => l.toUpperCase());
          
          if (value !== null && value !== undefined) {
            if (typeof value === 'object' && !Array.isArray(value)) {
              // Nested object - traverse deeper
              traverse(value, newPath, fieldName);
            } else if (Array.isArray(value)) {
              // Array field
              traverse(value, newPath, fieldName);
            } else {
              // Primitive field
              const fieldType = this.detectFieldType(value);
              fields.push({
                name: fieldName,
                path: newPath,
                type: fieldType,
                sampleValue: value
              });
            }
          }
        });
      }
    };
    
    traverse(data);
    return this.deduplicateFields(fields);
  }

  /**
   * Detect the type of a field value
   */
  private detectFieldType(value: any): string {
    if (typeof value === 'number') {
      return Number.isInteger(value) ? 'integer' : 'number';
    }
    if (typeof value === 'boolean') {
      return 'boolean';
    }
    if (typeof value === 'string') {
      // Check if it's a date string
      if (this.isDateString(value)) {
        return 'date';
      }
      // Check if it's a URL
      if (this.isUrl(value)) {
        return 'url';
      }
      return 'string';
    }
    return 'unknown';
  }

  /**
   * Check if string is a date
   */
  private isDateString(value: string): boolean {
    if (value.length < 10) return false;
    const date = new Date(value);
    return !isNaN(date.getTime()) && (
      value.includes('T') || 
      value.includes('-') || 
      value.includes('/')
    );
  }

  /**
   * Check if string is a URL
   */
  private isUrl(value: string): boolean {
    try {
      new URL(value);
      return true;
    } catch {
      return false;
    }
  }

  /**
   * Remove duplicate fields and prioritize shorter paths
   */
  private deduplicateFields(fields: FieldInfo[]): FieldInfo[] {
    const seen = new Map<string, FieldInfo>();
    
    fields.forEach(field => {
      const existing = seen.get(field.name);
      if (!existing || field.path.length < existing.path.length) {
        seen.set(field.name, field);
      }
    });
    
    return Array.from(seen.values()).sort((a, b) => {
      // Sort by type (numbers first, then strings, then others)
      const typeOrder = { 'number': 0, 'integer': 0, 'string': 1, 'date': 2, 'boolean': 3, 'unknown': 4 };
      const aOrder = typeOrder[a.type as keyof typeof typeOrder] ?? 5;
      const bOrder = typeOrder[b.type as keyof typeof typeOrder] ?? 5;
      
      if (aOrder !== bOrder) return aOrder - bOrder;
      return a.name.localeCompare(b.name);
    });
  }

  /**
   * Get suggestions for X/Y axis fields based on widget type
   */
  getSuggestedFields(fields: FieldInfo[], widgetType: string): { xAxis: FieldInfo[], yAxis: FieldInfo[] } {
    const dateFields = fields.filter(f => f.type === 'date');
    const numberFields = fields.filter(f => f.type === 'number' || f.type === 'integer');
    const stringFields = fields.filter(f => f.type === 'string');

    switch (widgetType) {
      case 'line_chart':
        return {
          xAxis: [...dateFields, ...stringFields],
          yAxis: numberFields
        };
      case 'bar_chart':
        return {
          xAxis: stringFields,
          yAxis: numberFields
        };
      case 'pie_chart':
        return {
          xAxis: stringFields,
          yAxis: numberFields
        };
      default:
        return {
          xAxis: fields,
          yAxis: numberFields
        };
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
