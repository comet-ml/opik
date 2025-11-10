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

export interface Trace {
  id: string;
  name: string;
  project_name?: string;
}

export interface TraceConfig {
  count: number;
  prefix: string;
  tags?: string[];
  metadata?: Record<string, any>;
  feedback_scores?: Array<{ name: string; value: number }>;
}

export interface SpanConfig {
  count: number;
  prefix: string;
  tags?: string[];
  metadata?: Record<string, any>;
  feedback_scores?: Array<{ name: string; value: number }>;
}

export interface ThreadConfig {
  thread_id: string;
  inputs: string[];
  outputs: string[];
}

export interface FeedbackDefinition {
  id: string;
  name: string;
  type: 'categorical' | 'numerical';
  details?: {
    categories?: Record<string, number>;
    min?: number;
    max?: number;
  };
}

export interface Experiment {
  id: string;
  name: string;
  dataset_name?: string;
}

export interface Prompt {
  name: string;
  prompt: string;
  commit?: string;
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

  // Dataset Items methods
  async insertDatasetItems(datasetName: string, items: Array<Record<string, any>>): Promise<void> {
    try {
      await this.client.post('/api/datasets/insert-items', {
        dataset_name: datasetName,
        items,
      });
    } catch (error) {
      throw this.handleError(error, 'Failed to insert dataset items');
    }
  }

  async getDatasetItems(datasetName: string): Promise<Array<Record<string, any>>> {
    try {
      const response = await this.client.post('/api/datasets/get-items', {
        dataset_name: datasetName,
      });
      return response.data.items;
    } catch (error) {
      throw this.handleError(error, 'Failed to get dataset items');
    }
  }

  async updateDatasetItems(datasetName: string, items: Array<Record<string, any>>): Promise<void> {
    try {
      await this.client.post('/api/datasets/update-items', {
        dataset_name: datasetName,
        items,
      });
    } catch (error) {
      throw this.handleError(error, 'Failed to update dataset items');
    }
  }

  async deleteDatasetItem(datasetName: string, itemId: string): Promise<void> {
    try {
      await this.client.delete('/api/datasets/delete-item', {
        data: {
          dataset_name: datasetName,
          item_id: itemId,
        },
      });
    } catch (error) {
      throw this.handleError(error, 'Failed to delete dataset item');
    }
  }

  async clearDataset(datasetName: string): Promise<void> {
    try {
      await this.client.post('/api/datasets/clear', {
        dataset_name: datasetName,
      });
    } catch (error) {
      throw this.handleError(error, 'Failed to clear dataset');
    }
  }

  async waitForDatasetItemsCount(
    datasetName: string,
    expectedCount: number,
    timeout: number = 10
  ): Promise<void> {
    try {
      const response = await this.client.post('/api/datasets/wait-for-items-count', {
        dataset_name: datasetName,
        expected_count: expectedCount,
        timeout,
      });

      if (!response.data.success) {
        throw new Error(response.data.error || 'Items count not reached within timeout');
      }
    } catch (error) {
      throw this.handleError(error, 'Failed to wait for dataset items count');
    }
  }

  // Experiment Items methods
  async getExperimentItems(experimentName: string, limit?: number): Promise<Array<Record<string, any>>> {
    try {
      const response = await this.client.post('/api/experiments/get-experiment-items', {
        experiment_name: experimentName,
        ...(limit ? { limit } : {}),
      });
      return response.data.items;
    } catch (error) {
      throw this.handleError(error, 'Failed to get experiment items');
    }
  }

  async deleteExperimentItems(itemIds: string[]): Promise<void> {
    try {
      await this.client.delete('/api/experiments/delete-experiment-items', {
        data: { ids: itemIds },
      });
    } catch (error) {
      throw this.handleError(error, 'Failed to delete experiment items');
    }
  }

  // Trace methods
  async createTracesDecorator(
    projectName: string,
    tracesNumber: number,
    prefix: string = 'test-trace-'
  ): Promise<number> {
    try {
      const response = await this.client.post('/api/traces/create-traces-decorator', {
        project_name: projectName,
        traces_number: tracesNumber,
        prefix,
      });

      return response.data.traces_created;
    } catch (error) {
      throw this.handleError(error, 'Failed to create traces via decorator');
    }
  }

  async createTracesClient(
    projectName: string,
    tracesNumber: number,
    prefix: string = 'test-trace-'
  ): Promise<number> {
    try {
      const response = await this.client.post('/api/traces/create-traces-client', {
        project_name: projectName,
        traces_number: tracesNumber,
        prefix,
      });

      return response.data.traces_created;
    } catch (error) {
      throw this.handleError(error, 'Failed to create traces via client');
    }
  }

  async createTracesWithSpansClient(
    projectName: string,
    traceConfig: TraceConfig,
    spanConfig: SpanConfig
  ): Promise<number> {
    try {
      const response = await this.client.post('/api/traces/create-traces-with-spans-client', {
        project_name: projectName,
        trace_config: traceConfig,
        span_config: spanConfig,
      });

      return response.data.traces_created;
    } catch (error) {
      throw this.handleError(error, 'Failed to create traces with spans via client');
    }
  }

  async createTracesWithSpansDecorator(
    projectName: string,
    traceConfig: TraceConfig,
    spanConfig: SpanConfig
  ): Promise<number> {
    try {
      const response = await this.client.post('/api/traces/create-traces-with-spans-decorator', {
        project_name: projectName,
        trace_config: traceConfig,
        span_config: spanConfig,
      });

      return response.data.traces_created;
    } catch (error) {
      throw this.handleError(error, 'Failed to create traces with spans via decorator');
    }
  }

  async createTraceWithAttachmentClient(
    projectName: string,
    attachmentPath: string
  ): Promise<string> {
    try {
      const response = await this.client.post('/api/traces/create-trace-with-attachment-client', {
        project_name: projectName,
        attachment_path: attachmentPath,
      });

      return response.data.attachment_name;
    } catch (error) {
      throw this.handleError(error, 'Failed to create trace with attachment via client');
    }
  }

  async createTraceWithAttachmentDecorator(
    projectName: string,
    attachmentPath: string
  ): Promise<string> {
    try {
      const response = await this.client.post('/api/traces/create-trace-with-attachment-decorator', {
        project_name: projectName,
        attachment_path: attachmentPath,
      });

      return response.data.attachment_name;
    } catch (error) {
      throw this.handleError(error, 'Failed to create trace with attachment via decorator');
    }
  }

  async createTraceWithSpanAttachment(
    projectName: string,
    attachmentPath: string
  ): Promise<{ attachmentName: string; spanName: string }> {
    try {
      const response = await this.client.post('/api/traces/create-trace-with-span-attachment', {
        project_name: projectName,
        attachment_path: attachmentPath,
      });

      return {
        attachmentName: response.data.attachment_name,
        spanName: response.data.span_name,
      };
    } catch (error) {
      throw this.handleError(error, 'Failed to create trace with span attachment');
    }
  }

  async getTraces(projectName: string, size: number = 10): Promise<Trace[]> {
    try {
      const response = await this.client.post('/api/traces/get-traces', {
        project_name: projectName,
        size,
      });

      return response.data.traces;
    } catch (error) {
      throw this.handleError(error, 'Failed to get traces');
    }
  }

  async deleteTraces(traceIds: string[]): Promise<number> {
    try {
      const response = await this.client.delete('/api/traces/delete-traces', {
        data: {
          trace_ids: traceIds,
        },
      });

      return response.data.deleted_count;
    } catch (error) {
      throw this.handleError(error, 'Failed to delete traces');
    }
  }

  async waitForTracesVisible(
    projectName: string,
    expectedCount: number,
    timeout: number = 30
  ): Promise<void> {
    try {
      const response = await this.client.post('/api/traces/wait-for-traces-visible', {
        project_name: projectName,
        expected_count: expectedCount,
        timeout,
      });

      if (!response.data.success) {
        throw new Error(response.data.error || 'Traces not visible within timeout');
      }
    } catch (error) {
      throw this.handleError(error, 'Failed to wait for traces visibility');
    }
  }

  // Thread methods
  async createThreadsDecorator(
    projectName: string,
    threadConfigs: ThreadConfig[]
  ): Promise<ThreadConfig[]> {
    try {
      const response = await this.client.post('/api/threads/create-threads-decorator', {
        project_name: projectName,
        thread_configs: threadConfigs,
      });

      return response.data.thread_configs;
    } catch (error) {
      throw this.handleError(error, 'Failed to create threads via decorator');
    }
  }

  async createThreadsClient(
    projectName: string,
    threadConfigs: ThreadConfig[]
  ): Promise<ThreadConfig[]> {
    try {
      const response = await this.client.post('/api/threads/create-threads-client', {
        project_name: projectName,
        thread_configs: threadConfigs,
      });

      return response.data.thread_configs;
    } catch (error) {
      throw this.handleError(error, 'Failed to create threads via client');
    }
  }

  // Feedback definition methods
  async createFeedbackDefinition(
    name: string,
    type: 'categorical' | 'numerical',
    options?: { categories?: Record<string, number>; min?: number; max?: number }
  ): Promise<FeedbackDefinition> {
    try {
      const response = await this.client.post('/api/feedback-scores/create-feedback-definition', {
        name,
        type,
        ...options,
      });

      return response.data;
    } catch (error) {
      throw this.handleError(error, 'Failed to create feedback definition');
    }
  }

  async getFeedbackDefinition(name: string): Promise<FeedbackDefinition> {
    try {
      const response = await this.client.get('/api/feedback-scores/get-feedback-definition', {
        params: { name },
      });

      return response.data;
    } catch (error) {
      throw this.handleError(error, 'Failed to get feedback definition');
    }
  }

  async updateFeedbackDefinition(
    id: string,
    name: string,
    options?: {
      type?: 'categorical' | 'numerical';
      categories?: Record<string, number>;
      min?: number;
      max?: number;
    }
  ): Promise<FeedbackDefinition> {
    try {
      const response = await this.client.post('/api/feedback-scores/update-feedback-definition', {
        id,
        name,
        ...options,
      });

      return response.data;
    } catch (error) {
      throw this.handleError(error, 'Failed to update feedback definition');
    }
  }

  async deleteFeedbackDefinition(id: string): Promise<void> {
    try {
      await this.client.delete('/api/feedback-scores/delete-feedback-definition', {
        data: { id },
      });
    } catch (error) {
      throw this.handleError(error, 'Failed to delete feedback definition');
    }
  }

  // Experiment methods
  async createExperiment(experimentName: string, datasetName: string): Promise<Experiment> {
    try {
      const response = await this.client.post('/api/experiments/create-experiment', {
        experiment_name: experimentName,
        dataset_name: datasetName,
      });

      return response.data;
    } catch (error) {
      throw this.handleError(error, 'Failed to create experiment');
    }
  }

  async getExperiment(experimentId: string): Promise<Experiment> {
    try {
      const response = await this.client.get('/api/experiments/get-experiment', {
        params: { experiment_id: experimentId },
      });

      return response.data;
    } catch (error) {
      throw this.handleError(error, 'Failed to get experiment');
    }
  }

  async deleteExperiment(experimentId: string): Promise<void> {
    try {
      await this.client.delete('/api/experiments/delete-experiment', {
        data: { experiment_id: experimentId },
      });
    } catch (error) {
      throw this.handleError(error, 'Failed to delete experiment');
    }
  }

  // Prompt methods
  async createPrompt(name: string, prompt: string): Promise<Prompt> {
    try {
      const response = await this.client.post('/api/prompts/create-prompt', {
        name,
        prompt,
      });

      return response.data;
    } catch (error) {
      throw this.handleError(error, 'Failed to create prompt');
    }
  }

  async getPrompt(name: string, commit?: string): Promise<Prompt> {
    try {
      const response = await this.client.get('/api/prompts/get-prompt', {
        params: { name, ...(commit ? { commit } : {}) },
      });

      return response.data;
    } catch (error) {
      throw this.handleError(error, 'Failed to get prompt');
    }
  }

  async updatePrompt(name: string, prompt: string): Promise<Prompt> {
    try {
      const response = await this.client.post('/api/prompts/update-prompt', {
        name,
        prompt,
      });

      return response.data;
    } catch (error) {
      throw this.handleError(error, 'Failed to update prompt');
    }
  }

  async deletePrompt(name: string): Promise<void> {
    try {
      await this.client.delete('/api/prompts/delete-prompt', {
        data: { name },
      });
    } catch (error) {
      throw this.handleError(error, 'Failed to delete prompt');
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
