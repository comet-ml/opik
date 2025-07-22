import apiClient from './api';
import { Dashboard, CreateDashboardRequest, UpdateDashboardRequest } from '@/types/dashboard';
import { QueryParams } from '@/types/api';

export class DashboardService {
  private basePath = '/dashboards';

  async getDashboards(params?: QueryParams) {
    return apiClient.get<Dashboard[]>(this.basePath, { params });
  }

  async getDashboard(id: string) {
    return apiClient.get<Dashboard>(`${this.basePath}/${id}`);
  }

  async createDashboard(data: CreateDashboardRequest) {
    return apiClient.post<Dashboard>(this.basePath, data);
  }

  async updateDashboard(id: string, data: UpdateDashboardRequest) {
    return apiClient.patch<Dashboard>(`${this.basePath}/${id}`, data);
  }

  async deleteDashboard(id: string) {
    return apiClient.delete(`${this.basePath}/${id}`);
  }

  async duplicateDashboard(id: string, name?: string) {
    return apiClient.post<Dashboard>(`${this.basePath}/${id}/duplicate`, { name });
  }

  async exportDashboard(id: string) {
    return apiClient.get<Dashboard>(`${this.basePath}/${id}/export`);
  }

  async importDashboard(data: Dashboard) {
    return apiClient.post<Dashboard>(`${this.basePath}/import`, data);
  }
}

export default new DashboardService();
