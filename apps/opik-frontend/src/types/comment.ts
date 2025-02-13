export type Comment = {
  id: string;
  text: string;
  created_at: string;
  last_updated_at: string;
  created_by: string;
  last_updated_by: string;
};

export type Comments = Comment[];
