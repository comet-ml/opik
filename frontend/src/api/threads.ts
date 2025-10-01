import axios from "axios";

/**
 * Bulk tagging threads (issue #3494)
 */
export async function batchTagThreads(
  threadIds: string[],
  add: string[],
  remove: string[]
) {
  const { data } = await axios.post("/api/threads/batch/tags", {
    thread_ids: threadIds,
    add,
    remove,
  });
  return data as { updated: string[]; failed: { id: string; error: string }[] };
}

/**
 * Fetch threads with optional filters (issue #3493)
 * 
 * @param filters.hasComment - if true, only threads with comments
 *                             if false, only threads without comments
 *                             if undefined, no filter applied
 */
export async function fetchThreads(filters: { hasComment?: boolean } = {}) {
  const { data } = await axios.get("/api/threads", {
    params: {
      has_comment: filters.hasComment,
    },
  });
  return data as {
    id: string;
    title: string;
    tags: string[];
    comment?: string;
  }[];
}