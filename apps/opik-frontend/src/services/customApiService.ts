import {
  CustomApiInstance,
  CreateCustomApiRequest,
  UpdateCustomApiRequest,
  ListCustomApiResponse,
  CustomApiTestResult,
  CustomApiError,
} from '@/types/customApi';

const BASE_URL = 'http://localhost:9080/api';

class CustomApiService {
  /**
   * Create a new custom API instance
   */
  async createInstance(request: CreateCustomApiRequest): Promise<CustomApiInstance> {
    try {
      const response = await fetch(`${BASE_URL}/create-instance`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          instance_name: request.instance_name,
          code: request.code,
        }),
      });

      if (!response.ok) {
        const errorData = await response.text();
        throw new Error(`Failed to create instance: ${errorData}`);
      }

      const result = await response.json();
      
      return {
        instance_name: request.instance_name,
        code: request.code,
        description: request.description,
        status: 'active',
        created_at: new Date().toISOString(),
        ...result,
      };
    } catch (error) {
      throw new Error(`Error creating custom API: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
  }

  /**
   * List all custom API instances
   */
  async listInstances(): Promise<CustomApiInstance[]> {
    try {
      const response = await fetch(`${BASE_URL}/list-instances`);

      if (!response.ok) {
        throw new Error(`Failed to list instances: ${response.statusText}`);
      }

      const result = await response.json();
      
      // Handle the actual API response format
      if (result.instances && Array.isArray(result.instances)) {
        return result.instances.map((instance: any) => ({
          instance_name: instance.name || instance.instance_name,
          code: instance.code || '# Code not available - this API was created externally',
          status: instance.status || 'active',
          created_at: instance.created_at,
          updated_at: instance.updated_at,
          description: instance.description,
          endpoint: instance.endpoint,
          last_executed: instance.last_executed,
          timeout: instance.timeout,
        }));
      } else if (Array.isArray(result)) {
        return result.map((instance: any) => ({
          instance_name: instance.name || instance.instance_name || instance,
          code: instance.code || '# Code not available - this API was created externally',
          status: instance.status || 'active',
          created_at: instance.created_at,
          updated_at: instance.updated_at,
          description: instance.description,
          endpoint: instance.endpoint,
          last_executed: instance.last_executed,
          timeout: instance.timeout,
        }));
      } else {
        return [];
      }
    } catch (error) {
      throw new Error(`Error listing custom APIs: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
  }

  /**
   * Update an existing custom API instance
   */
  async updateInstance(request: UpdateCustomApiRequest): Promise<CustomApiInstance> {
    try {
      const response = await fetch(`${BASE_URL}/update-instance`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          instance_name: request.instance_name,
          code: request.code,
        }),
      });

      if (!response.ok) {
        const errorData = await response.text();
        throw new Error(`Failed to update instance: ${errorData}`);
      }

      const result = await response.json();
      
      return {
        instance_name: request.instance_name,
        code: request.code,
        description: request.description,
        status: 'active',
        updated_at: new Date().toISOString(),
        ...result,
      };
    } catch (error) {
      throw new Error(`Error updating custom API: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
  }

  /**
   * Delete a custom API instance
   */
  async deleteInstance(instanceName: string): Promise<void> {
    try {
      const response = await fetch(`${BASE_URL}/${instanceName}`, {
        method: 'DELETE',
      });

      if (!response.ok) {
        const errorData = await response.text();
        throw new Error(`Failed to delete instance: ${errorData}`);
      }
    } catch (error) {
      throw new Error(`Error deleting custom API: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
  }

  /**
   * Call/test a custom API instance
   */
  async callInstance(instanceName: string): Promise<CustomApiTestResult> {
    const startTime = Date.now();
    
    try {
      const response = await fetch(`${BASE_URL}/${instanceName}`);
      const executionTime = Date.now() - startTime;

      if (!response.ok) {
        const errorData = await response.text();
        return {
          success: false,
          error: `API call failed: ${errorData}`,
          execution_time: executionTime,
        };
      }

      const data = await response.json();
      
      return {
        success: true,
        data,
        execution_time: executionTime,
      };
    } catch (error) {
      const executionTime = Date.now() - startTime;
      return {
        success: false,
        error: `Error calling API: ${error instanceof Error ? error.message : 'Unknown error'}`,
        execution_time: executionTime,
      };
    }
  }

  /**
   * Validate instance name
   */
  validateInstanceName(name: string): { valid: boolean; error?: string } {
    if (!name) {
      return { valid: false, error: 'Instance name is required' };
    }
    
    if (!/^[a-zA-Z0-9_-]+$/.test(name)) {
      return { valid: false, error: 'Instance name can only contain letters, numbers, hyphens, and underscores' };
    }
    
    if (name.length < 3 || name.length > 50) {
      return { valid: false, error: 'Instance name must be between 3 and 50 characters' };
    }
    
    return { valid: true };
  }

  /**
   * Validate Python code (basic validation)
   */
  validatePythonCode(code: string): { valid: boolean; error?: string } {
    if (!code || code.trim().length === 0) {
      return { valid: false, error: 'Python code is required' };
    }
    
    // Basic validation - ensure it ends with result assignment or return
    const trimmedCode = code.trim();
    if (!trimmedCode.includes('result') && !trimmedCode.includes('return')) {
      return { valid: false, error: 'Code should assign a result variable or use return statement' };
    }
    
    return { valid: true };
  }

  /**
   * Generate API endpoint URL for a given instance
   */
  getApiEndpoint(instanceName: string): string {
    return `${BASE_URL}/${instanceName}`;
  }
}

export default new CustomApiService(); 