import { FilterOperator } from "@/types/filters";
import { AggregatedFeedbackScore } from "@/types/shared";

export enum ANNOTATION_QUEUE_SCOPE {
  TRACE = "trace",
  THREAD = "thread",
}

export interface AnnotationQueueReviewer {
  username: string;
  status: number;
}

export enum ANNOTATION_QUEUE_ITEM_SOURCE {
  MANUAL = "manual",
  AUTOMATED = "automated",
}

export interface AnnotationQueueItem {
  id: string;
  source: ANNOTATION_QUEUE_ITEM_SOURCE;
}

export interface AnnotationQueueAutomationFilter {
  field: string;
  operator: FilterOperator;
  key?: string;
  value: string;
}

/**
 * Rules for populating a queue automatically: an item is added when it matches every filter of any one
 * group. A queue with automation enabled still accepts manual additions.
 */
export interface AnnotationQueueAutomation {
  enabled: boolean;
  /** Absent means "leave the stored groups alone", which is what a toggle-only request sends. */
  filter_groups?: AnnotationQueueAutomationFilter[][];
  /**
   * Automation stops adding once the queue holds this many items. Absent means "leave the stored
   * ceiling alone", as absent does for every field here; send clear_max_items_in_queue to remove one.
   */
  max_items_in_queue?: number | null;
  /** Write-only: removes the stored ceiling. Cannot be sent together with max_items_in_queue. */
  clear_max_items_in_queue?: boolean;
}

export interface AnnotationQueueAnnotatorView {
  html?: string;
  kit_version?: number;
  summary?: string;
}

export interface AnnotationQueue {
  id: string;
  project_id: string;
  project_name: string;
  name: string;
  description?: string;
  instructions?: string;
  comments_enabled: boolean;
  feedback_definition_names: string[];
  annotators_per_item?: number; // optional for opik v1 compat
  lock_timeout_seconds?: number; // optional for opik v1 compat
  scope: ANNOTATION_QUEUE_SCOPE;
  automation?: AnnotationQueueAutomation;
  annotator_view?: AnnotationQueueAnnotatorView;
  reviewers?: AnnotationQueueReviewer[];
  feedback_scores?: AggregatedFeedbackScore[];
  items_count: number;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
  last_scored_at: string;
}

export interface AnnotationQueueItemLockResponse {
  acquired: boolean;
  item_id: string;
  locked_by: string;
  expires_at: string | null;
}

export interface AnnotationQueueItemLockInfo {
  active_locks: number;
  locked_by: string[];
}

export interface AnnotationQueueLocksResponse {
  locks: Record<string, AnnotationQueueItemLockInfo>;
}

export type CreateAnnotationQueue = Omit<
  AnnotationQueue,
  | "id"
  | "project_name"
  | "reviewers"
  | "feedback_scores"
  | "items_count"
  | "created_at"
  | "created_by"
  | "last_updated_at"
  | "last_updated_by"
  | "last_scored_at"
>;

export type UpdateAnnotationQueue = Partial<
  Omit<
    AnnotationQueue,
    | "id"
    | "project_id"
    | "project_name"
    | "scope"
    | "reviewers"
    | "feedback_scores"
    | "items_count"
    | "created_at"
    | "created_by"
    | "last_updated_at"
    | "last_updated_by"
    | "last_scored_at"
  >
>;
