import React from "react";
import WidgetConfigForm from "./WidgetConfigForm";
import WidgetConfigPreview from "./WidgetConfigPreview";
import {
  AddWidgetConfig,
  WidgetEditorComponent,
  WidgetEditorHandle,
} from "@/types/dashboard";

interface WidgetConfigDialogEditStepProps {
  widgetData: AddWidgetConfig;
  onChange: (data: Partial<AddWidgetConfig>) => void;
  editorComponent: WidgetEditorComponent | null;
  editorRef: React.RefObject<WidgetEditorHandle>;
}

const WidgetConfigDialogEditStep: React.FunctionComponent<
  WidgetConfigDialogEditStepProps
> = ({ widgetData, onChange, editorComponent, editorRef }) => {
  return (
    <div className="grid h-[600px] grid-cols-[3fr_2fr] gap-4">
      <div className="overflow-hidden">
        <WidgetConfigForm
          widgetData={widgetData}
          onChange={onChange}
          editorComponent={editorComponent}
          editorRef={editorRef}
        />
      </div>

      <div className="overflow-hidden">
        <WidgetConfigPreview widgetData={widgetData} />
      </div>
    </div>
  );
};

export default WidgetConfigDialogEditStep;
