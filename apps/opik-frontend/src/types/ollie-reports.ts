export enum ReportStatus {
  PENDING = "pending",
  COMPLETED = "completed",
  FAILED = "failed",
}

export interface OllieReport {
  id: string;
  project_id: string;
  session_id?: string;
  content?: string;
  status: ReportStatus;
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
  schedule_time_utc: string;
  created_at?: string;
  last_updated_at?: string;
}

export interface GenerateReportResponse {
  report_id: string;
}
