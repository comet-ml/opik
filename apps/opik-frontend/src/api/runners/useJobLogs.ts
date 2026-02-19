import { useCallback, useEffect, useRef, useState } from "react";
import api, { RUNNERS_REST_ENDPOINT } from "@/api/api";
import { LogEntry } from "@/types/runners";

type UseJobLogsParams = {
  jobId: string;
  enabled: boolean;
  streaming: boolean;
};

export default function useJobLogs({
  jobId,
  enabled,
  streaming,
}: UseJobLogsParams) {
  const [lines, setLines] = useState<LogEntry[]>([]);
  const offsetRef = useRef(0);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const fetchedOnceRef = useRef(false);

  useEffect(() => {
    offsetRef.current = 0;
    fetchedOnceRef.current = false;
    setLines([]);
  }, [jobId]);

  const poll = useCallback(async () => {
    try {
      const { data } = await api.get<LogEntry[]>(
        `${RUNNERS_REST_ENDPOINT}jobs/${jobId}/logs`,
        { params: { offset: offsetRef.current } },
      );
      if (data.length > 0) {
        offsetRef.current += data.length;
        setLines((prev) => [...prev, ...data]);
      }
    } catch {
      // Silently ignore polling errors
    }
  }, [jobId]);

  useEffect(() => {
    if (!enabled) {
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
        intervalRef.current = null;
      }
      return;
    }

    if (streaming) {
      poll();
      intervalRef.current = setInterval(poll, 500);
      return () => {
        if (intervalRef.current) {
          clearInterval(intervalRef.current);
          intervalRef.current = null;
        }
      };
    } else if (!fetchedOnceRef.current) {
      fetchedOnceRef.current = true;
      poll();
    }
  }, [enabled, streaming, poll]);

  return { lines };
}
