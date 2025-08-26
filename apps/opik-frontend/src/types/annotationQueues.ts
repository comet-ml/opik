export interface AnnotationQueue {
  id: string;
  name: string;
  description?: string;
  status: "active" | "completed" | "paused";
  created_by: string;
  project_id: string;
  template_id?: string;
  visible_fields: string[];
  required_metrics: string[];
  optional_metrics: string[];
  instructions?: string;
  due_date?: string;
  created_at: string;
  updated_at: string;
  total_items: number;
  completed_items: number;
  assigned_smes: string[];
  share_url?: string;
}

export interface AnnotationQueueCreate {
  name: string;
  description?: string;
  template_id?: string;
  visible_fields?: string[];
  required_metrics?: string[];
  optional_metrics?: string[];
  instructions?: string;
  due_date?: string;
}

export type QueueItemStatus = "pending" | "in_progress" | "completed" | "skipped";

export interface QueueItem {
  id: string;
  queue_id: string;
  item_type: "trace" | "thread";
  item_id: string;
  status: QueueItemStatus;
  assigned_sme?: string;
  created_at: string;
  completed_at?: string;
  trace_data?: any; // Will be populated with actual trace data
  thread_data?: any; // Will be populated with actual thread data
}

export interface QueueItemCreate {
  item_type: "trace" | "thread";
  item_id: string;
}

export interface QueueItemsBatch {
  items: QueueItemCreate[];
}

export interface Annotation {
  id: string;
  queue_item_id: string;
  sme_id: string;
  metrics: Record<string, any>;
  comment?: string;
  created_at: string;
  updated_at: string;
}

export interface AnnotationCreate {
  metrics: Record<string, any>;
  comment?: string;
}