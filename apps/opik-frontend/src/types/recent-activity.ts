export enum ActivityType {
  EXPERIMENT = "experiment",
  DATASET_VERSION = "dataset_version",
  TEST_SUITE_VERSION = "test_suite_version",
  ALERT_EVENT = "alert_event",
  OPTIMIZATION = "optimization",
  AGENT_CONFIG_VERSION = "agent_config_version",
}

export interface RecentActivityItem {
  type: ActivityType;
  id: string;
  name: string;
  created_at: string;
  resource_id?: string;
  created_by?: string;
}

export interface RecentActivityResponse {
  page: number;
  size: number;
  total: number;
  content: RecentActivityItem[];
}
