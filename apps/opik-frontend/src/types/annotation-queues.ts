import { AggregatedFeedbackScore } from "@/types/shared";

export enum ANNOTATION_QUEUE_SCOPE {
  TRACE = "trace",
  THREAD = "thread",
}

export interface AnnotationQueueReviewer {
  username: string;
  status: number;
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
  scope: ANNOTATION_QUEUE_SCOPE;
  reviewers?: AnnotationQueueReviewer[];
  feedback_scores?: AggregatedFeedbackScore[];
  items_count: number;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
  last_scored_at: string;
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
