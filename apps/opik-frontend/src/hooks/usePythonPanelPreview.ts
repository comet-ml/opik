import { useState, useCallback } from "react";
import useExperimentById from "@/api/datasets/useExperimentById";

interface UsePythonPanelPreviewOptions {
  experimentId?: string;
}

export function usePythonPanelPreview(options?: UsePythonPanelPreviewOptions) {
  const [loading, setLoading] = useState(false);
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  
  const { experimentId } = options || {};
  
  // Fetch experiment data if experimentId is provided
  const { data: experimentData } = useExperimentById(
    { experimentId: experimentId || "" },
    { enabled: !!experimentId }
  );

  const getPreviewUrl = useCallback(async (code: string) => {
    setLoading(true);
    setError(null);
    try {
      // Replace {CONNECTION_CONFIG} with environment variable settings
      const connectionConfig = `os.environ["OPIK_URL_OVERRIDE"] = "http://host.docker.internal:8080/"
os.environ["OPIK_API_KEY"] = "opik-1234567890"`;
      
      // Use actual experiment name if available, otherwise use fallback
      const currentExperimentName = experimentData?.name || "Demo experiment";
      const filterConfig = `experimentsNames[0]= "${currentExperimentName}"
      `;
      
      const codeWithConfig = code.replace('# {CONNECTION_CONFIG}', connectionConfig);
      const processedCode = codeWithConfig.replace('# {FILTER_CONFIG}', filterConfig);
      
      // Generate instanceId as hash of the processed code
      const encoder = new TextEncoder();
      const encodedData = encoder.encode(processedCode);
      const hashBuffer = await crypto.subtle.digest('SHA-256', encodedData);
      const hashArray = Array.from(new Uint8Array(hashBuffer));
      
      // Python panel filename must start with letters!!
      const instanceId = "page_" + hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
      
      const res = await fetch("http://localhost:9080/api/get-python-panel-url", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ 
          code: processedCode,
          instanceId: instanceId  
        }),
      });
      const responseData = await res.json();
      if (res.ok) {
        setUrl(responseData.url);
      } else {
        setError(responseData.error || "Failed to get preview URL");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "An error occurred");
    } finally {
      setLoading(false);
    }
  }, [experimentData?.name]);

  return { url, loading, error, getPreviewUrl };
} 