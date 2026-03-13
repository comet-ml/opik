export enum PROMPT_TEMPLATE_STRUCTURE {
  CHAT = "chat",
  TEXT = "text",
}

export enum PROMPT_TYPE {
  MUSTACHE = "mustache",
  JINJA2 = "jinja2",
}

export enum PROMPT_VERSION_ACTION {
  NO_ACTION = "no_action",
  UPDATE_BLUEPRINT = "update_blueprint",
}

export interface Prompt {
  id: string;
  name: string;
  description: string;
  last_updated_at: string;
  created_at: string;
  version_count: number;
  tags: string[];
  template_structure?: PROMPT_TEMPLATE_STRUCTURE;
  latest_version?: PromptVersion;
}

export type PromptWithLatestVersion = Prompt & {
  latest_version?: PromptVersion;
};

export interface PromptVersion {
  id: string;
  template: string;
  metadata: object;
  commit: string;
  change_description?: string;
  prompt_id: string;
  created_at: string;
  tags?: string[];
  type?: PROMPT_TYPE;
}

export interface PromptCommitInfo {
  prompt_version_id?: string;
  commit: string;
  prompt_id?: string;
  prompt_name?: string;
}

export interface PromptVersionByCommit {
  id: string;
  commit: string;
  template: string;
  metadata: object | null;
  type?: PROMPT_TYPE;
  change_description?: string;
  variables?: string[];
  created_at: string;
  created_by: string;
}

export interface PromptByCommit {
  id: string;
  name: string;
  template_structure?: PROMPT_TEMPLATE_STRUCTURE;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
  version_count: number;
  requested_version: PromptVersionByCommit;
}
