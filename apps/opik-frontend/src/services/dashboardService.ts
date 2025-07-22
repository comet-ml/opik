import api from '@/api/api';
import { DASHBOARDS_REST_ENDPOINT } from '@/api/api';
import { Dashboard, CreateDashboardRequest, UpdateDashboardRequest } from '@/types/dashboard';
import { QueryParams } from '@/types/api';

// Backend API response types
interface BackendDashboard {
  id: string;
  name: string;
  description: string;
  layout: {
    grid: Array<{
      id: string;
      x: number;
      y: number;
      w: number;
      h: number;
      type: string;
      config: {
        title: string;
        data_source?: string;
        query_params?: Record<string, any>;
        chart_options?: Record<string, any>;
        refresh_interval?: number;
      };
    }>;
  };
  filters: Record<string, any>;
  refresh_interval: number;
  created_at?: string;
  created_by?: string;
  last_updated_at?: string;
  last_updated_by?: string;
}

interface BackendDashboardPage {
  content: BackendDashboard[];
  page: number;
  size: number;
  total: number;
}

export class DashboardService {
  private basePath = DASHBOARDS_REST_ENDPOINT;

  private transformFromBackend(backendDashboard: BackendDashboard): Dashboard {
    const transformed = {
      id: backendDashboard.id,
      name: backendDashboard.name,
      description: backendDashboard.description,
      layout: {
        grid: backendDashboard.layout.grid.map(widget => {
          return {
            id: widget.id || `widget_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`, // Generate ID if missing
            x: widget.x,
            y: widget.y,
            w: widget.w,
            h: widget.h,
            type: widget.type as any,
            config: {
              title: widget.config.title,
              dataSource: widget.config.data_source || '', // Convert null to empty string for UI
              queryParams: widget.config.query_params || {},
              chartOptions: widget.config.chart_options || {},
              refreshInterval: widget.config.refresh_interval,
            }
          };
        })
      },
      filters: backendDashboard.filters || {},
      refreshInterval: backendDashboard.refresh_interval,
      createdAt: backendDashboard.created_at,
      createdBy: backendDashboard.created_by,
      lastUpdatedAt: backendDashboard.last_updated_at,
      lastUpdatedBy: backendDashboard.last_updated_by,
      // Legacy fields for compatibility
      created: backendDashboard.created_at,
      modified: backendDashboard.last_updated_at,
    };
    
    return transformed;
  }

  private transformToBackend(dashboard: Partial<Dashboard>): any {
    const payload: any = {};
    
    if (dashboard.name) payload.name = dashboard.name;
    if (dashboard.description !== undefined) payload.description = dashboard.description;
    if (dashboard.refreshInterval) payload.refresh_interval = dashboard.refreshInterval;
    if (dashboard.filters) payload.filters = dashboard.filters;
    
    if (dashboard.layout) {
      payload.layout = {
        grid: dashboard.layout.grid.map(widget => {
          return {
            id: widget.id,
            x: widget.x,
            y: widget.y,
            w: widget.w,
            h: widget.h,
            type: widget.type,
            config: {
              title: widget.config.title,
              data_source: widget.config.dataSource || null, // Convert empty string/undefined to null
              query_params: widget.config.queryParams || {},
              chart_options: widget.config.chartOptions || {},
              refresh_interval: widget.config.refreshInterval,
            }
          };
        })
      };
    }
    
    return payload;
  }

  async getDashboards(params?: QueryParams) {
    const response = await api.get<BackendDashboardPage>(this.basePath, { params });
    return {
      ...response,
      data: response.data.content.map(dashboard => this.transformFromBackend(dashboard)),
      content: response.data.content.map(dashboard => this.transformFromBackend(dashboard)),
      page: response.data.page,
      size: response.data.size,
      total: response.data.total,
    };
  }

  async getDashboard(id: string) {
    const response = await api.get<BackendDashboard>(`${this.basePath}${id}`, {});
    return {
      ...response,
      data: this.transformFromBackend(response.data),
    };
  }

  async createDashboard(data: CreateDashboardRequest) {
    const response = await api.post<BackendDashboard>(this.basePath, data);
    return {
      ...response,
      data: this.transformFromBackend(response.data),
    };
  }

  async updateDashboard(id: string, data: UpdateDashboardRequest) {
    const transformedData = this.transformToBackend(data);
    const response = await api.patch<BackendDashboard>(`${this.basePath}${id}`, transformedData);
    return {
      ...response,
      data: this.transformFromBackend(response.data),
    };
  }

  async deleteDashboard(id: string) {
    return api.delete(`${this.basePath}${id}`);
  }

  async duplicateDashboard(id: string, name?: string) {
    const response = await api.post<BackendDashboard>(`${this.basePath}${id}/duplicate`, { name });
    return {
      ...response,
      data: this.transformFromBackend(response.data),
    };
  }

  async exportDashboard(id: string) {
    const response = await api.get<BackendDashboard>(`${this.basePath}${id}/export`, {});
    return {
      ...response,
      data: this.transformFromBackend(response.data),
    };
  }

  async importDashboard(data: Dashboard) {
    const transformedData = this.transformToBackend(data);
    const response = await api.post<BackendDashboard>(`${this.basePath}import`, transformedData);
    return {
      ...response,
      data: this.transformFromBackend(response.data),
    };
  }
}

export default new DashboardService();
