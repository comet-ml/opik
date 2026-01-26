import React from "react";
import { EditorLayoutProportion } from "@/types/dashboard";
import { cn } from "@/lib/utils";

interface WidgetEditorLayoutProps {
  children: React.ReactNode;
  proportion?: EditorLayoutProportion;
}

interface ColumnProps {
  children: React.ReactNode;
}

const PROPORTION_CLASSES: Record<EditorLayoutProportion, string> = {
  "preview-small": "grid-cols-[3fr_2fr]", // 60% editor, 40% preview
  "preview-large": "grid-cols-[2fr_3fr]", // 40% editor, 60% preview
};

const WidgetEditorLayout: React.FC<WidgetEditorLayoutProps> & {
  LeftColumn: React.FC<ColumnProps>;
  RightColumn: React.FC<ColumnProps>;
  AbovePreview: React.FC<ColumnProps>;
  Preview: React.FC<ColumnProps>;
} = ({ children, proportion = "preview-small" }) => {
  const gridColsClass = PROPORTION_CLASSES[proportion];

  return (
    <div
      className={cn(
        "grid h-[50vh] max-h-[600px] min-h-[300px] gap-4",
        gridColsClass,
      )}
    >
      {children}
    </div>
  );
};

const LeftColumn: React.FC<ColumnProps> = ({ children }) => {
  return <div className="overflow-y-auto">{children}</div>;
};
LeftColumn.displayName = "WidgetEditorLayout.LeftColumn";
WidgetEditorLayout.LeftColumn = LeftColumn;

const RightColumn: React.FC<ColumnProps> = ({ children }) => {
  return (
    <div className="flex h-full flex-col gap-3 overflow-hidden">{children}</div>
  );
};
RightColumn.displayName = "WidgetEditorLayout.RightColumn";
WidgetEditorLayout.RightColumn = RightColumn;

const AbovePreview: React.FC<ColumnProps> = ({ children }) => {
  return (
    <div className="shrink-0 space-y-3 rounded-md border bg-muted/30 p-4">
      {children}
    </div>
  );
};
AbovePreview.displayName = "WidgetEditorLayout.AbovePreview";
WidgetEditorLayout.AbovePreview = AbovePreview;

const Preview: React.FC<ColumnProps> = ({ children }) => {
  return <div className="min-h-0 flex-1 overflow-hidden">{children}</div>;
};
Preview.displayName = "WidgetEditorLayout.Preview";
WidgetEditorLayout.Preview = Preview;

export default WidgetEditorLayout;
