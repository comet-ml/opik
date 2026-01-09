import React from "react";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Description } from "@/components/ui/description";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";

interface WidgetOverrideDefaultsSectionProps {
  value: boolean;
  onChange: (enabled: boolean) => void;
  description: string;
  children: React.ReactNode;
}

const WidgetOverrideDefaultsSection: React.FC<
  WidgetOverrideDefaultsSectionProps
> = ({ value, onChange, description, children }) => {
  return (
    <Accordion
      type="single"
      collapsible
      className="border-t"
      defaultValue={value ? "advanced" : undefined}
    >
      <AccordionItem value="advanced">
        <AccordionTrigger className="h-11 py-1.5">
          Advanced settings
        </AccordionTrigger>
        <AccordionContent className="px-3 pb-3">
          <div className="space-y-3">
            <div className="flex items-start justify-between">
              <div className="flex-1 pr-4">
                <div className="flex flex-col gap-0.5 px-0.5">
                  <Label className="comet-body-s-accented">
                    Override dashboard default data source
                  </Label>
                  <Description>{description}</Description>
                </div>
              </div>
              <Switch checked={value} onCheckedChange={onChange} size="sm" />
            </div>
            {value && (
              <div className="rounded-md border border-border p-3">
                {children}
              </div>
            )}
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default WidgetOverrideDefaultsSection;
