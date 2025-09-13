export enum AnnotationQueueScope {
  TRACE = "trace",
  THREAD = "thread",
}

export interface AnnotationQueueReviewer {
  username: string;
  status: number;
}

export interface AggregatedFeedbackScore {
  name: string;
  value: number;
}

export interface AnnotationQueue {
  id: string;
  project_id: string;
  project_name?: string;
  name: string;
  description?: string;
  instructions?: string;
  comments_enabled: boolean;
  feedback_definitions: string[];
  scope: AnnotationQueueScope;
  reviewers?: AnnotationQueueReviewer[];
  feedback_scores?: AggregatedFeedbackScore[];
  items_count?: number;
  share_token?: string;
  is_public?: boolean;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
  last_scored_at?: string;
}

export interface AnnotationQueueUpdate {
  name: string;
  description?: string;
  instructions?: string;
  comments_enabled: boolean;
  feedback_definitions: string[];
}

export interface AnnotationQueueItemsAdd {
  ids: string[];
}

export interface AnnotationQueueItemsDelete {
  ids: string[];
}

export interface AnnotationQueuesBatchDelete {
  ids: string[];
}

export type AnnotationQueuesResponse = {
  content: AnnotationQueue[];
  sortable_by: string[];
  total: number;
};

// SME-specific types
export interface SMEAnnotationQueue {
  id: string;
  name: string;
  description?: string;
  instructions?: string;
  comments_enabled: boolean;
  feedback_definitions: string[];
  scope: AnnotationQueueScope;
  items_count?: number;
}

export interface SMEQueueProgress {
  total_items: number;
  completed_items: number;
  progress_percentage: number;
}

export interface SMEAnnotationSubmission {
  feedback_scores: Array<{
    name: string;
    value: number;
    reason?: string;
  }>;
  comment?: string;
}
