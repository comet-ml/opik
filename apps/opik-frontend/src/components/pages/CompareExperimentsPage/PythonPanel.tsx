import React, { useMemo } from "react";
import { PythonPanelConfig } from "./dashboardTypes";

interface PythonPanelProps {
  config: PythonPanelConfig;
  id: string;
}

const PythonPanel: React.FC<PythonPanelProps> = ({ config, id }) => {
  // Memoize the code preview content
  const codePreview = useMemo(() => {
    return config.code;
  }, [config.code]);

  // Memoize the placeholder output content
  const placeholderContent = useMemo(() => (
    <div className="bg-card border rounded-md p-4 text-center">
      <div className="text-4xl mb-2">📊</div>
      <p className="comet-body-s text-muted-foreground mb-3">
        Python execution output will appear here
      </p>
      <div className="comet-body-xs text-muted-foreground bg-muted/50 p-3 rounded-sm space-y-1">
        <p>• Matplotlib plots and visualizations</p>
        <p>• Print statements and console output</p>
        <p>• Data analysis results</p>
      </div>
    </div>
  ), []);

  return (
    <div className="h-full flex flex-col bg-background">
      {/* Status Header */}
      <div className="p-3 bg-primary-100/50 border-b">
        <div className="flex items-center gap-2">
          <div className="text-primary">🐍</div>
          <span className="comet-body-s text-primary">Python Panel Ready</span>
          <span className="comet-body-xs text-muted-foreground bg-muted px-2 py-1 rounded-sm">
            Placeholder
          </span>
        </div>
      </div>

      {/* Code Preview */}
      <div className="flex-1 p-4 overflow-auto">
        <div className="mb-4">
          <h4 className="comet-body-s font-medium text-foreground mb-2">Python Code:</h4>
          <pre className="bg-muted text-foreground p-3 rounded-md text-xs overflow-auto max-h-32 font-mono border">
            {codePreview}
          </pre>
        </div>

        {/* Placeholder Output */}
        <div>
          <h4 className="comet-body-s font-medium text-foreground mb-2">Expected Output:</h4>
          {placeholderContent}
        </div>
      </div>

      {/* Footer */}
      <div className="p-3 bg-accent/50 border-t">
        <p className="comet-body-xs text-muted-foreground text-center">
          🔧 Ready for backend integration - Python code will execute when connected to server
        </p>
      </div>
    </div>
  );
};

export default React.memo(PythonPanel); 
