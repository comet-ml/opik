import React from "react";
import WidgetEditorLayout from "./WidgetEditorLayout";
import WidgetConfigPreview from "./WidgetConfigPreview";
import { EditorLayoutProportion } from "@/types/dashboard";

interface WidgetEditorBaseLayoutProps {
  children: React.ReactNode;
  proportion?: EditorLayoutProportion;
}

const WidgetEditorBaseLayout: React.FC<WidgetEditorBaseLayoutProps> = ({
  children,
  proportion,
}) => {
  return (
    <WidgetEditorLayout proportion={proportion}>
      <WidgetEditorLayout.LeftColumn>{children}</WidgetEditorLayout.LeftColumn>

      <WidgetEditorLayout.RightColumn>
        <WidgetEditorLayout.Preview>
          <WidgetConfigPreview />
        </WidgetEditorLayout.Preview>
      </WidgetEditorLayout.RightColumn>
    </WidgetEditorLayout>
  );
};

export default WidgetEditorBaseLayout;
