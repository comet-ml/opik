import { useState, useCallback } from "react";

export function usePythonPanelPreview() {
  const [loading, setLoading] = useState(false);
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const getPreviewUrl = useCallback(async (code: string) => {
    setLoading(true);
    setError(null);
    try {
      // Replace {CONNECTION_CONFIG} with environment variable settings
      const connectionConfig = `os.environ["OPIK_URL_OVERRIDE"] = "http://host.docker.internal:8080/"
os.environ["OPIK_API_KEY"] = "opik-1234567890"`;
      
     const filterConfig = `experimentsNames[0]= "Demo experiment"
      `;
      const codeWithConfig = code.replace('# {CONNECTION_CONFIG}', connectionConfig);
      const processedCode = codeWithConfig.replace('# {FILTER_CONFIG}', filterConfig);
      
      // Create hash of the processed code for instanceId
      const encoder = new TextEncoder();
      const encodedData = encoder.encode(processedCode);
      const hashBuffer = await crypto.subtle.digest('SHA-256', encodedData);
      const hashArray = Array.from(new Uint8Array(hashBuffer));
      const instanceId = hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
      
      const res = await fetch("http://localhost:9080/api/get-python-panel-url", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ 
          code: processedCode,
          instanceId: instanceId
        }),
      });
      if (!res.ok) throw new Error("Failed to get preview URL");
      const responseData = await res.json();
      setUrl(responseData.url);
    } catch (e: any) {
      setError(e.message);
      setUrl(null);
    } finally {
      setLoading(false);
    }
  }, []);

  return { url, loading, error, getPreviewUrl };
} 