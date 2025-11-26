import React from "react";
import { WidgetEditorComponent } from "@/types/dashboard";
import { AddWidgetConfig } from "@/types/dashboard";

interface WidgetConfigFormProps {
  widgetData: AddWidgetConfig;
  onChange: (data: Partial<AddWidgetConfig>) => void;
  editorComponent: WidgetEditorComponent | null;
}

const WidgetConfigForm: React.FunctionComponent<WidgetConfigFormProps> = ({
  widgetData,
  onChange,
  editorComponent: EditorComponent,
}) => {
  if (!EditorComponent) {
    return (
      <div className="flex h-full items-center justify-center p-8">
        <p className="text-sm text-muted-foreground">
          Configuration form will appear here
        </p>
      </div>
    );
  }

  return (
    <div className="h-full overflow-auto p-4">
      <EditorComponent {...widgetData} onChange={onChange} />
    </div>
  );
};

export default WidgetConfigForm;
