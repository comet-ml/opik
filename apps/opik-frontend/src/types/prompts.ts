export interface Prompt {
  id: string;
  name: string;
  description: string;
  last_updated_at: string;
  created_at: string;
  version_count: number;

  latest_version?: PromptVersion;
}

export type PromptWithLatestVersion = Prompt & {
  latest_version?: PromptVersion;
};

export interface PromptVersion {
  id: string;
  created_at: string;
  template: string;
}
