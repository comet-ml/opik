import React from "react";
import WidgetExampleSection from "./WidgetExampleSection";
import type { ViewTree, SourceData } from "@/lib/data-view/core/types";
import { allExamples } from "./examples";

interface WidgetExample {
  title: string;
  tree: ViewTree;
  sourceData: SourceData;
}

// Examples imported from the examples folder
const examples: WidgetExample[] = allExamples;

const CustomViewWidgetsPage: React.FC = () => {
  return (
    <div className="flex h-full flex-col">
      <div className="border-b px-6 py-4">
        <h1 className="text-xl font-semibold">Custom View Widgets</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Showcase of all available data-view widgets with their JSON structure
          and rendered output.
        </p>
      </div>
      <div className="flex-1 overflow-auto p-6">
        {examples.length === 0 ? (
          <div className="flex h-full items-center justify-center">
            <p className="text-muted-foreground">
              No widget examples available yet.
            </p>
          </div>
        ) : (
          <div className="flex flex-col gap-4">
            {examples.map((example, index) => (
              <WidgetExampleSection
                key={index}
                title={example.title}
                tree={example.tree}
                sourceData={example.sourceData}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default CustomViewWidgetsPage;
