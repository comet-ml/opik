import React from "react";
import WidgetEditorLayout from "./WidgetEditorLayout";
import WidgetConfigPreview from "./WidgetConfigPreview";

interface WidgetEditorBaseLayoutProps {
  children: React.ReactNode;
}

const WidgetEditorBaseLayout: React.FC<WidgetEditorBaseLayoutProps> = ({
  children,
}) => {
  return (
    <WidgetEditorLayout>
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
