/**
 * Test Helper Client
 * Type-safe HTTP client for communicating with Flask test helper service
 */

import axios, { AxiosInstance, AxiosError } from 'axios';
import { getEnvironmentConfig } from '../config/env.config';

export interface SdkConfig {
  workspace?: string;
  host?: string;
  api_key?: string;
}

export interface Project {
  id?: string;
  name: string;
}

export interface Dataset {
  id: string;
  name: string;
}

export interface ApiResponse<T = any> {
  success: boolean;
  data?: T;
  error?: string;
  type?: string;
}

export class TestHelperClient {
  private client: AxiosInstance;
  private config: SdkConfig;

  constructor() {
    const envConfig = getEnvironmentConfig();
    const envData = envConfig.getConfig();

    this.config = {
      workspace: envData.workspace,
      host: envConfig.getApiUrl(),
      api_key: envData.apiKey,
    };

    this.client = axios.create({
      baseURL: envConfig.getTestHelperUrl(),
      timeout: 30000,
      headers: {
        'Content-Type': 'application/json',
      },
    });
  }

  async healthCheck(): Promise<boolean> {
    try {
      const response = await this.client.get('/health');
      return response.data.status === 'healthy';
    } catch (error) {
      return false;
    }
  }

  async createProject(name: string): Promise<Project> {
    try {
      const response = await this.client.post('/api/projects/create', {
        config: this.config,
        name,
      });

      if (!response.data.success) {
        throw new Error(response.data.error || 'Failed to create project');
      }

      return response.data.project;
    } catch (error) {
      throw this.handleError(error, 'Failed to create project');
    }
  }

  async findProject(name: string): Promise<Project[]> {
    try {
      const response = await this.client.post('/api/projects/find', {
        config: this.config,
        name,
      });

      if (!response.data.success) {
        throw new Error(response.data.error || 'Failed to find project');
      }

      return response.data.projects;
    } catch (error) {
      throw this.handleError(error, 'Failed to find project');
    }
  }

  async deleteProject(name: string): Promise<void> {
    try {
      const response = await this.client.delete('/api/projects/delete', {
        data: {
          config: this.config,
          name,
        },
      });

      if (!response.data.success) {
        throw new Error(response.data.error || 'Failed to delete project');
      }
    } catch (error) {
      throw this.handleError(error, 'Failed to delete project');
    }
  }

  async updateProject(name: string, newName: string): Promise<Project> {
    try {
      const response = await this.client.post('/api/projects/update', {
        config: this.config,
        name,
        new_name: newName,
      });

      if (!response.data.success) {
        throw new Error(response.data.error || 'Failed to update project');
      }

      return response.data.project;
    } catch (error) {
      throw this.handleError(error, 'Failed to update project');
    }
  }

  async waitForProjectVisible(name: string, timeout: number = 10): Promise<void> {
    try {
      const response = await this.client.post('/api/projects/wait-for-visible', {
        config: this.config,
        name,
        timeout,
      });

      if (!response.data.success) {
        throw new Error(response.data.error || 'Project not visible within timeout');
      }
    } catch (error) {
      throw this.handleError(error, 'Failed to wait for project visibility');
    }
  }

  async waitForProjectDeleted(name: string, timeout: number = 10): Promise<void> {
    try {
      const response = await this.client.post('/api/projects/wait-for-deleted', {
        config: this.config,
        name,
        timeout,
      });

      if (!response.data.success) {
        throw new Error(response.data.error || 'Project still exists after timeout');
      }
    } catch (error) {
      throw this.handleError(error, 'Failed to wait for project deletion');
    }
  }

  async createDataset(name: string): Promise<Dataset> {
    try {
      const response = await this.client.post('/api/datasets/create', {
        name,
      });

      return response.data;
    } catch (error) {
      throw this.handleError(error, 'Failed to create dataset');
    }
  }

  async findDataset(name: string): Promise<Dataset | null> {
    try {
      const response = await this.client.post('/api/datasets/find', {
        name,
      });

      return response.data;
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.status === 404) {
        return null;
      }
      throw this.handleError(error, 'Failed to find dataset');
    }
  }

  async deleteDataset(name: string): Promise<void> {
    try {
      await this.client.delete('/api/datasets/delete', {
        data: {
          name,
        },
      });
    } catch (error) {
      throw this.handleError(error, 'Failed to delete dataset');
    }
  }

  async updateDataset(name: string, newName: string): Promise<Dataset> {
    try {
      const response = await this.client.post('/api/datasets/update', {
        name,
        newName,
      });

      return response.data;
    } catch (error) {
      throw this.handleError(error, 'Failed to update dataset');
    }
  }

  async waitForDatasetVisible(name: string, timeout: number = 10): Promise<Dataset> {
    try {
      const response = await this.client.post('/api/datasets/wait-for-visible', {
        name,
        timeout,
      });

      if (response.data.error) {
        throw new Error(response.data.error);
      }

      return response.data;
    } catch (error) {
      throw this.handleError(error, 'Failed to wait for dataset visibility');
    }
  }

  async waitForDatasetDeleted(name: string, timeout: number = 10): Promise<void> {
    try {
      const response = await this.client.post('/api/datasets/wait-for-deleted', {
        name,
        timeout,
      });

      if (!response.data.success) {
        throw new Error(response.data.error || 'Dataset still exists after timeout');
      }
    } catch (error) {
      throw this.handleError(error, 'Failed to wait for dataset deletion');
    }
  }

  private handleError(error: unknown, context: string): Error {
    if (axios.isAxiosError(error)) {
      const axiosError = error as AxiosError<ApiResponse>;

      if (axiosError.response?.data?.error) {
        const errorData = axiosError.response.data;
        return new Error(
          `${context}: ${errorData.error} (${errorData.type || 'Unknown'})`
        );
      }

      if (axiosError.code === 'ECONNREFUSED') {
        return new Error(
          `${context}: Flask test helper service is not running. ` +
          `Please ensure the service is started at ${this.client.defaults.baseURL}`
        );
      }

      return new Error(`${context}: ${axiosError.message}`);
    }

    return new Error(`${context}: ${(error as Error).message}`);
  }
}
