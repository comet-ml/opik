export type CommentItem = {
  id: string;
  text: string;
  created_at: string;
  last_updated_at: string;
  created_by: string;
  last_updated_by: string;
  source_queue_id?: string;
};

export type CommentItems = CommentItem[];
