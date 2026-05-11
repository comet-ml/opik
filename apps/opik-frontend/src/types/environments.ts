export const ENVIRONMENT_NAME_REGEX = /^[A-Za-z0-9_-]+$/;
export const ENVIRONMENT_NAME_MAX_LENGTH = 150;
export const ENVIRONMENT_DESCRIPTION_MAX_LENGTH = 500;
export const ENVIRONMENT_WORKSPACE_LIMIT = 20;

export interface Environment {
  id: string;
  name: string;
  description: string | null;
  color: string;
  position: number;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}
