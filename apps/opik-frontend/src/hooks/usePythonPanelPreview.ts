import { useState, useCallback } from "react";

export function usePythonPanelPreview() {
  const [loading, setLoading] = useState(false);
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const getPreviewUrl = useCallback(async (code: string) => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("http://localhost:9080/api/get-python-panel-url", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code }),
      });
      if (!res.ok) throw new Error("Failed to get preview URL");
      const data = await res.json();
      setUrl(data.url);
    } catch (e: any) {
      setError(e.message);
      setUrl(null);
    } finally {
      setLoading(false);
    }
  }, []);

  return { url, loading, error, getPreviewUrl };
} 