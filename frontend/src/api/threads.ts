import axios from "axios";

export async function batchTagThreads(threadIds: string[], add: string[], remove: string[]) {
  const { data } = await axios.post("/api/threads/batch/tags", {
    thread_ids: threadIds,
    add,
    remove,
  });
  return data as { updated: string[]; failed: { id: string; error: string }[] };
}