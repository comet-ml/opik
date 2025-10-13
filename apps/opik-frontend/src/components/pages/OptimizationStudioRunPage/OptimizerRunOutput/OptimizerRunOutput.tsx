import React from "react";
import useOptimizationStudioRunLogs from "@/api/optimization-studio/useOptimizationStudioRunLogs";
import useAppStore from "@/store/AppStore";
import Loader from "@/components/shared/Loader/Loader";
import { formatDate } from "@/lib/date";

interface OptimizerRunOutputProps {
  runId?: string;
}

const OptimizerRunOutput: React.FC<OptimizerRunOutputProps> = ({ runId }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data, isPending } = useOptimizationStudioRunLogs(
    {
      runId: runId!,
      workspaceName,
    },
    {
      enabled: Boolean(runId),
      refetchInterval: 2000, // Poll every 2 seconds for real-time updates
    }
  );

  if (!runId) {
    return (
      <div className="flex flex-col gap-4">
        <h2 className="comet-body-s-accented">Run Output</h2>
        <div className="flex flex-col gap-2">
          <p className="comet-body-s text-muted-slate">
            No optimization run selected
          </p>
        </div>
      </div>
    );
  }

  if (isPending) {
    return (
      <div className="flex flex-col gap-4">
        <h2 className="comet-body-s-accented">Run Output</h2>
        <Loader />
      </div>
    );
  }

  const logs = data?.content ?? [];

  const renderAnsiText = (text: string) => {
    // Parse ANSI codes and OSC 8 hyperlinks
    // OSC 8 format: \u001B]8;id=X;URL\u001B\\text\u001B]8;;\u001B\\
    const parts: React.ReactNode[] = [];
    let pos = 0;
    let currentStyle: React.CSSProperties = {};
    let partKey = 0;

    while (pos < text.length) {
      // Check for OSC 8 hyperlink: \u001B]8;
      const hyperlinkMatch = text.substring(pos).match(/^\u001B\]8;([^;]*);([^\u001B]+)\u001B\\/);
      if (hyperlinkMatch) {
        const url = hyperlinkMatch[2];
        const hyperlinkStart = pos + hyperlinkMatch[0].length;

        // Find the closing sequence: \u001B]8;;\u001B\\
        const closeMatch = text.substring(hyperlinkStart).indexOf('\u001B]8;;\u001B\\');

        if (closeMatch !== -1) {
          const linkText = text.substring(hyperlinkStart, hyperlinkStart + closeMatch);
          parts.push(
            <a
              key={`link-${partKey++}`}
              href={url}
              target="_blank"
              rel="noopener noreferrer"
              className="text-blue-500 hover:underline"
              style={currentStyle}
            >
              {linkText}
            </a>
          );
          pos = hyperlinkStart + closeMatch + 7; // Skip past closing sequence
          continue;
        }
      }

      // Check for ANSI color codes: \u001B[XXm
      const ansiMatch = text.substring(pos).match(/^\u001B\[(\d+)m/);
      if (ansiMatch) {
        const codeNum = parseInt(ansiMatch[1], 10);
        if (codeNum === 0) {
          currentStyle = {}; // Reset
        } else if (codeNum === 2) {
          currentStyle = { ...currentStyle, opacity: 0.5 }; // Dim
        } else if (codeNum === 36) {
          currentStyle = { ...currentStyle, color: "#06b6d4" }; // Cyan
        } else if (codeNum === 31) {
          currentStyle = { ...currentStyle, color: "#ef4444" }; // Red
        } else if (codeNum === 33) {
          currentStyle = { ...currentStyle, color: "#eab308" }; // Yellow
        } else if (codeNum === 32) {
          currentStyle = { ...currentStyle, color: "#22c55e" }; // Green
        }
        pos += ansiMatch[0].length;
        continue;
      }

      // Regular text - find next escape sequence
      let nextSpecial = text.length;
      const nextEscape = text.indexOf("\u001B", pos);

      if (nextEscape !== -1) nextSpecial = nextEscape;

      if (nextSpecial > pos) {
        const textPart = text.substring(pos, nextSpecial);
        parts.push(
          <span key={`text-${partKey++}`} style={currentStyle}>
            {textPart}
          </span>
        );
        pos = nextSpecial;
      } else {
        pos++; // Skip unrecognized escape sequence
      }
    }

    return parts.length > 0 ? parts : text;
  };

  return (
    <div className="flex flex-col gap-4 h-full">
      <div className="flex items-center justify-between">
        <h2 className="comet-body-s-accented">Run Output</h2>
        <span className="comet-body-s text-muted-slate">
          {logs.length} log{logs.length !== 1 ? "s" : ""}
        </span>
      </div>
      <div className="flex-1 rounded border bg-muted/30 overflow-auto">
        <div className="p-4 font-mono text-xs leading-tight">
          {logs.length === 0 ? (
            <p className="text-muted-slate">No logs yet...</p>
          ) : (
            logs.map((log, index) => (
              <div key={index} className="whitespace-pre-wrap break-all">
                {renderAnsiText(log.message)}
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
};

export default OptimizerRunOutput;
