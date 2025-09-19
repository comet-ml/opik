export interface ModelComparison {
  id: string;
  name: string;
  description?: string;
  modelAId: string;
  modelBId: string;
  modelAName: string;
  modelBName: string;
  modelAMetrics?: Record<string, number>;
  modelBMetrics?: Record<string, number>;
  modelACost?: number;
  modelBCost?: number;
  createdAt: string;
  lastUpdatedAt: string;
  createdBy?: string;
  lastUpdatedBy?: string;
  metadata?: Record<string, any>;
}

export interface ModelComparisonCreateRequest {
  name: string;
  description?: string;
  modelAId: string;
  modelBId: string;
  modelAName: string;
  modelBName: string;
  metadata?: Record<string, any>;
}

export interface ModelComparisonUpdateRequest {
  name?: string;
  description?: string;
  modelAMetrics?: Record<string, number>;
  modelBMetrics?: Record<string, number>;
  modelACost?: number;
  modelBCost?: number;
  metadata?: Record<string, any>;
}