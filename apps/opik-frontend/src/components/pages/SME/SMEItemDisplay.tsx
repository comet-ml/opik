import React, { useState } from "react";

import { Button } from "@/components/ui/button";
import { Tag } from "@/components/ui/tag";
import { ChevronDown, ChevronUp, Eye, EyeOff } from "lucide-react";

import { AnnotationQueueScope } from "@/types/annotation-queues";
import { formatDate } from "@/lib/date";

type SMEItemDisplayProps = {
  item: Record<string, unknown>;
  scope: AnnotationQueueScope;
};

const SMEItemDisplay: React.FunctionComponent<SMEItemDisplayProps> = ({
  item,
  scope,
}) => {
  const [showMetadata, setShowMetadata] = useState(false);
  const [expandedSections, setExpandedSections] = useState<Record<string, boolean>>({
    input: true,
    output: true,
  });

  const toggleSection = (section: string) => {
    setExpandedSections(prev => ({
      ...prev,
      [section]: !prev[section],
    }));
  };

  const renderValue = (value: unknown, key?: string): React.ReactNode => {
    if (value === null || value === undefined) {
      return <span className="text-muted-foreground italic">null</span>;
    }

    if (typeof value === "string") {
      return <span className="font-mono text-sm">{value}</span>;
    }

    if (typeof value === "number" || typeof value === "boolean") {
      return <span className="font-mono text-sm">{String(value)}</span>;
    }

    if (typeof value === "object") {
      return (
        <pre className="text-xs bg-muted p-2 rounded overflow-auto max-h-32">
          {JSON.stringify(value, null, 2)}
        </pre>
      );
    }

    return <span className="font-mono text-sm">{String(value)}</span>;
  };

  const renderSection = (title: string, content: unknown, sectionKey: string) => {
    if (!content) return null;

    const isExpanded = expandedSections[sectionKey];

    return (
      <div className="border rounded-md">
        <Button 
          variant="ghost" 
          className="w-full justify-between p-2"
          onClick={() => toggleSection(sectionKey)}
        >
          <span className="font-medium">{title}</span>
          {isExpanded ? (
            <ChevronUp className="h-4 w-4" />
          ) : (
            <ChevronDown className="h-4 w-4" />
          )}
        </Button>
        {isExpanded && (
          <div className="px-2 pb-2 border-t">
            {renderValue(content)}
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="space-y-6">
      {/* Item Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center space-x-3">
          <Tag variant="gray" size="sm">
            {scope === AnnotationQueueScope.TRACE ? "Trace" : "Thread"}
          </Tag>
          {typeof item.name === "string" && item.name && (
            <h3 className="text-base font-medium text-gray-900">{item.name}</h3>
          )}
        </div>

        <Button
          variant="outline"
          size="sm"
          onClick={() => setShowMetadata(!showMetadata)}
          className="text-xs"
        >
          {showMetadata ? (
            <>
              <EyeOff className="mr-1 h-3 w-3" />
              Hide metadata
            </>
          ) : (
            <>
              <Eye className="mr-1 h-3 w-3" />
              Show metadata
            </>
          )}
        </Button>
      </div>

      {/* Main Content Sections */}
      <div className="space-y-4">
        {/* Input Section */}
        <div className="space-y-2">
          <h4 className="text-sm font-medium text-gray-700">Input</h4>
          <div className="bg-gray-50 rounded-lg p-4 border">
            {renderValue(item.input)}
          </div>
        </div>

        {/* Output Section */}
        <div className="space-y-2">
          <h4 className="text-sm font-medium text-gray-700">Output</h4>
          <div className="bg-gray-50 rounded-lg p-4 border">
            {renderValue(item.output)}
          </div>
        </div>

        {/* Metadata Sections (Collapsible) */}
        {showMetadata && (
          <div className="space-y-3 pt-2 border-t border-gray-200">
            {item.metadata ? (
              <div className="space-y-2">
                <h4 className="text-sm font-medium text-gray-700">Metadata</h4>
                <div className="bg-gray-50 rounded-lg p-4 border text-xs">
                  {renderValue(item.metadata)}
                </div>
              </div>
            ) : null}

            {item.tags ? (
              <div className="space-y-2">
                <h4 className="text-sm font-medium text-gray-700">Tags</h4>
                <div className="bg-gray-50 rounded-lg p-4 border text-xs">
                  {renderValue(item.tags)}
                </div>
              </div>
            ) : null}

            {item.usage ? (
              <div className="space-y-2">
                <h4 className="text-sm font-medium text-gray-700">Usage</h4>
                <div className="bg-gray-50 rounded-lg p-4 border text-xs">
                  {renderValue(item.usage)}
                </div>
              </div>
            ) : null}

            {item.error_info ? (
              <div className="space-y-2">
                <h4 className="text-sm font-medium text-gray-700">Error Info</h4>
                <div className="bg-red-50 rounded-lg p-4 border border-red-200 text-xs">
                  {renderValue(item.error_info)}
                </div>
              </div>
            ) : null}

            {(item.start_time || item.created_at) ? (
              <div className="space-y-2">
                <h4 className="text-sm font-medium text-gray-700">Timestamps</h4>
                <div className="bg-gray-50 rounded-lg p-4 border text-xs space-y-1">
                  {typeof item.start_time === "string" && item.start_time && (
                    <div><strong>Started:</strong> {formatDate(item.start_time)}</div>
                  )}
                  {typeof item.end_time === "string" && item.end_time && (
                    <div><strong>Ended:</strong> {formatDate(item.end_time)}</div>
                  )}
                  {typeof item.created_at === "string" && item.created_at && (
                    <div><strong>Created:</strong> {formatDate(item.created_at)}</div>
                  )}
                </div>
              </div>
            ) : null}
          </div>
        )}
      </div>
    </div>
  );
};

export default SMEItemDisplay;
