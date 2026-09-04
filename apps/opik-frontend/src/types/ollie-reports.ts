export enum ReportStatus {
  PENDING = "pending",
  COMPLETED = "completed",
  FAILED = "failed",
}

export const OUT_OF_CREDITS_FAILURE_REASON = "out_of_credits";

export interface RecommendedAction {
  name: string;
  description: string;
  prompt: string;
}

export interface OllieReport {
  id: string;
  project_id: string;
  session_id?: string;
  content?: string;
  recommended_actions?: RecommendedAction[];
  status: ReportStatus;
  failure_reason?: string;
  created_at: string;
  last_updated_at: string;
}

export interface OllieReportPage {
  page: number;
  size: number;
  total: number;
  content: OllieReport[];
}

export interface ReportPreference {
  project_id: string;
  enabled: boolean;
  schedule_time: string;
  custom_prompt?: string;
  created_at?: string;
  last_updated_at?: string;
}

export interface GenerateReportResponse {
  report_id: string;
}

export interface ReportPreferenceSettings {
  enabled: boolean;
  scheduleTime: string;
  customPrompt: string;
}
