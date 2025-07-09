import React, { useMemo, useEffect } from "react";
import { PythonPanelConfig } from "./dashboardTypes";
import { usePythonPanelPreview } from "@/hooks/usePythonPanelPreview";

interface PythonPanelProps {
  config: PythonPanelConfig;
  id: string;
}

const PythonPanel: React.FC<PythonPanelProps> = ({ config, id }) => {
  // Use the preview hook to get live Streamlit preview
  const { url: previewUrl, loading: previewLoading, error: previewError, getPreviewUrl } = usePythonPanelPreview();

  // Memoize the code preview content
  const codePreview = useMemo(() => {
    return config.code;
  }, [config.code]);

  // Fetch preview URL when component mounts or code changes
  useEffect(() => {
    if (config.code) {
      getPreviewUrl(config.code);
    }
  }, [config.code, getPreviewUrl]);

  // Memoize the status indicator
  const statusIndicator = useMemo(() => {
    if (previewLoading) {
      return {
        label: "Loading",
        color: "text-orange-600",
        bgColor: "bg-orange-100",
        icon: "⏳"
      };
    }
    if (previewError) {
      return {
        label: "Error",
        color: "text-red-600",
        bgColor: "bg-red-100",
        icon: "❌"
      };
    }
    if (previewUrl) {
      return {
        label: "Running",
        color: "text-green-600",
        bgColor: "bg-green-100",
        icon: "✅"
      };
    }
    return {
      label: "Ready",
      color: "text-gray-600",
      bgColor: "bg-gray-100",
      icon: "🐍"
    };
  }, [previewLoading, previewError, previewUrl]);

  return (
    <div className="h-full flex flex-col bg-background">
      {/* Status Header */}
      <div className="p-3 bg-primary-100/50 border-b">
        <div className="flex items-center gap-2">
          <div className="text-primary">{statusIndicator.icon}</div>
          <span className="comet-body-s text-primary">Python Panel</span>
          <span className={`comet-body-xs px-2 py-1 rounded-sm ${statusIndicator.bgColor} ${statusIndicator.color}`}>
            {statusIndicator.label}
          </span>
          {previewUrl && (
            <button
              onClick={() => window.open(previewUrl, '_blank')}
              className="ml-auto comet-body-xs text-primary hover:text-primary-dark underline"
            >
              Open in new tab ↗
            </button>
          )}
        </div>
      </div>

      {/* Main Content */}
      <div className="flex-1 overflow-hidden">
        {previewLoading ? (
          <div className="h-full flex flex-col items-center justify-center bg-card">
            <div className="text-4xl mb-4">⏳</div>
            <p className="comet-body-s text-muted-foreground mb-2">
              Starting your Streamlit app...
            </p>
            <p className="comet-body-xs text-muted-foreground">
              This may take a few moments
            </p>
          </div>
        ) : previewError ? (
          <div className="h-full p-4 overflow-auto">
            <div className="bg-red-50 border border-red-200 rounded-md p-4 mb-4">
              <div className="flex items-center gap-2 mb-2">
                <span className="text-red-600">❌</span>
                <h4 className="comet-body-s font-medium text-red-800">Failed to load Streamlit app</h4>
              </div>
              <p className="text-red-700 text-xs">
                {previewError}
              </p>
            </div>
            
            {/* Show code for debugging */}
            <div>
              <h4 className="comet-body-s font-medium text-foreground mb-2">Python Code:</h4>
              <pre className="bg-muted text-foreground p-3 rounded-md text-xs overflow-auto max-h-32 font-mono border">
                {codePreview}
              </pre>
            </div>
          </div>
        ) : previewUrl ? (
          <iframe
            src={previewUrl}
            className="w-full h-full border-0"
            title={`Python Panel ${id}`}
            sandbox="allow-scripts allow-same-origin allow-forms allow-downloads"
          />
        ) : (
          <div className="h-full flex flex-col items-center justify-center bg-card">
            <div className="text-4xl mb-4">🐍</div>
            <p className="comet-body-s text-muted-foreground mb-2">
              No preview available
            </p>
            <p className="comet-body-xs text-muted-foreground">
              Check your Python code and try again
            </p>
          </div>
        )}
      </div>

      {/* Footer */}
      <div className="p-3 bg-accent/50 border-t">
        <p className="comet-body-xs text-muted-foreground text-center">
          {previewUrl ? (
            <>🚀 Live Streamlit app running at {previewUrl}</>
          ) : (
            <>🔧 Python Panel Engine integration active</>
          )}
        </p>
      </div>
    </div>
  );
};

export default React.memo(PythonPanel); 
