export enum PROMPT_TEMPLATE_STRUCTURE {
  CHAT = "chat",
  TEXT = "text",
}

export enum PROMPT_TYPE {
  MUSTACHE = "mustache",
  JINJA2 = "jinja2",
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
