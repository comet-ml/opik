import { AggregatedFeedbackScore } from "@/types/shared";
import { Filter } from "@/types/filters";

export enum ANNOTATION_QUEUE_SCOPE {
  TRACE = "trace",
  THREAD = "thread",
}

export enum ANNOTATION_QUEUE_TYPE {
  MANUAL = "manual",
  DYNAMIC = "dynamic",
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
  queue_type?: ANNOTATION_QUEUE_TYPE;
  filter_criteria?: Filter[];
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
> & {
  queue_type?: ANNOTATION_QUEUE_TYPE;
  filter_criteria?: Filter[];
};

export type UpdateAnnotationQueue = Partial<
  Omit<
    AnnotationQueue,
    | "id"
    | "project_id"
    | "project_name"
    | "scope"
    | "queue_type"
    | "filter_criteria"
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
