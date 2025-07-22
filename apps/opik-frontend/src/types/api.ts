export interface ApiResponse<T = any> {
  data: T;
  success: boolean;
  message?: string;
  errors?: string[];
}

export interface ApiError {
  message: string;
  code?: string;
  status?: number;
  details?: Record<string, any>;
}

export interface PaginationParams {
  page: number;
  limit: number;
  sortBy?: string;
  sortOrder?: 'asc' | 'desc';
}

export interface PaginationResponse {
  page: number;
  totalPages: number;
  totalItems: number;
  limit: number;
}

export interface FilterParams {
  dateRange?: {
    start: string;
    end: string;
  };
  projectId?: string;
  status?: string[];
  [key: string]: any;
}

export interface QueryParams extends PaginationParams, FilterParams {
  search?: string;
}
