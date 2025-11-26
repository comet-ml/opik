import React from "react";
import WidgetConfigForm from "./WidgetConfigForm";
import WidgetConfigPreview from "./WidgetConfigPreview";
import { AddWidgetConfig, WidgetEditorComponent } from "@/types/dashboard";

interface WidgetConfigDialogEditStepProps {
  widgetData: AddWidgetConfig;
  onChange: (data: Partial<AddWidgetConfig>) => void;
  editorComponent: WidgetEditorComponent | null;
}

const WidgetConfigDialogEditStep: React.FunctionComponent<
  WidgetConfigDialogEditStepProps
> = ({ widgetData, onChange, editorComponent }) => {
  return (
    <div className="grid h-[600px] grid-cols-[3fr_2fr] gap-4">
      <div className="overflow-hidden rounded-md border border-border">
        <WidgetConfigForm
          widgetData={widgetData}
          onChange={onChange}
          editorComponent={editorComponent}
        />
      </div>

      <div className="overflow-hidden">
        <WidgetConfigPreview widgetData={widgetData} />
      </div>
    </div>
  );
};

export default WidgetConfigDialogEditStep;
