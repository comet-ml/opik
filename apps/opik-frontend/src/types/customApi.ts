export interface CustomApiInstance {
  instance_name: string;
  code: string;
  created_at?: string;
  updated_at?: string;
  status?: 'active' | 'inactive' | 'error';
  description?: string;
  endpoint?: string;
  last_executed?: string;
  timeout?: number;
}

export interface CreateCustomApiRequest {
  instance_name: string;
  code: string;
  description?: string;
}

export interface UpdateCustomApiRequest {
  instance_name: string;
  code: string;
  description?: string;
}

export interface ListCustomApiResponse {
  instances: CustomApiInstance[];
}

export interface CustomApiTestResult {
  success: boolean;
  data?: any;
  error?: string;
  execution_time?: number;
}

export interface CustomApiError {
  message: string;
  code?: string;
  details?: any;
} 