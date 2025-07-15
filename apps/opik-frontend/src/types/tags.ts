export interface Tag {
  id: string;
  name: string;
  description?: string;
  workspace_id: string;
  created_at: string;
  updated_at: string;
}

export interface TagCreate {
  name: string;
  description?: string;
}

export interface TagUpdate {
  name: string;
  description?: string;
}