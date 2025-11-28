import React from "react";
import {
  WidgetEditorComponent,
  WidgetEditorHandle,
  AddWidgetConfig,
} from "@/types/dashboard";

interface WidgetConfigFormProps {
  widgetData: AddWidgetConfig;
  onChange: (data: Partial<AddWidgetConfig>) => void;
  editorComponent: WidgetEditorComponent | null;
  editorRef: React.RefObject<WidgetEditorHandle>;
}

const WidgetConfigForm: React.FunctionComponent<WidgetConfigFormProps> = ({
  widgetData,
  onChange,
  editorComponent: EditorComponent,
  editorRef,
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
    <div className="size-full overflow-auto">
      <EditorComponent ref={editorRef} {...widgetData} onChange={onChange} />
    </div>
  );
};

export default WidgetConfigForm;
