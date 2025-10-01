import React, { useEffect, useState } from "react";
import ThreadTable from "../../components/threads/ThreadTable";

type Thread = { id: string; title: string; tags: string[] };

// Replace with actual data loader
async function fetchThreads(): Promise<Thread[]> {
  // Example: call your existing API
  return [];
}

// Simple toast stub; replace with app's toaster
function useToast() {
  return {
    show: (msg: string, type?: "success" | "warning" | "error") => {
      console.log(`[${type || "info"}] ${msg}`);
    },
  };
}

export default function ThreadsPage() {
  const [threads, setThreads] = useState<Thread[]>([]);
  const toast = useToast();

  useEffect(() => {
    fetchThreads().then(setThreads);
  }, []);

  const onTagsUpdated = (updatedIds: string[], add: string[], remove: string[]) => {
    setThreads((prev) =>
      prev.map((t) => {
        if (!updatedIds.includes(t.id)) return t;
        const final = new Set(t.tags);
        remove.forEach((r) => final.delete(r));
        add.forEach((a) => final.add(a));
        return { ...t, tags: Array.from(final) };
      })
    );
  };

  return (
    <div>
      <h2>Threads</h2>
      <ThreadTable
        threads={threads}
        onTagsUpdated={onTagsUpdated}
        showToast={(msg, type) => toast.show(msg, type)}
      />
    </div>
  );
}