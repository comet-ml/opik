import React from "react";
import get from "lodash/get";
import {
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import DashboardDataSourceSection from "@/components/pages-shared/dashboards/DashboardDataSourceSection/DashboardDataSourceSection";
import { Control } from "react-hook-form";
import { cn } from "@/lib/utils";
import { DASHBOARD_TEMPLATES } from "@/lib/dashboard/templates";
import { TEMPLATE_TYPE } from "@/types/dashboard";
import DashboardTemplateCard from "./DashboardTemplateCard";

interface DashboardFormFields {
  name: string;
  description?: string;
  templateType?: string;
}

interface DashboardDialogDetailsStepProps {
  control: Control<DashboardFormFields>;
  templateType?: string;
  showDataSourceSection?: boolean;
  descriptionExpanded?: boolean;
  onSubmit: () => void;
}

const DashboardDialogDetailsStep: React.FunctionComponent<
  DashboardDialogDetailsStepProps
> = ({
  control,
  templateType,
  showDataSourceSection,
  descriptionExpanded,
  onSubmit,
}) => {
  const template = templateType
    ? DASHBOARD_TEMPLATES[templateType as TEMPLATE_TYPE]
    : null;

  return (
    <div className="flex flex-col gap-4">
      {template && (
        <FormField
          control={control}
          name="templateType"
          render={() => (
            <FormItem>
              <FormLabel>Template</FormLabel>
              <DashboardTemplateCard
                name={template.name}
                description={template.description}
                icon={template.icon}
                iconColor={template.iconColor}
              />
            </FormItem>
          )}
        />
      )}

      <FormField
        control={control}
        name="name"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["name"]);

          return (
            <FormItem>
              <FormLabel>Name</FormLabel>
              <FormControl>
                <Input
                  {...field}
                  placeholder="Dashboard name"
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") {
                      e.preventDefault();
                      onSubmit();
                    }
                  }}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          );
        }}
      />

      <Accordion
        type="multiple"
        defaultValue={descriptionExpanded ? ["description"] : []}
        className="border-t"
      >
        <AccordionItem value="description">
          <AccordionTrigger className="h-11 py-1.5">
            Description
          </AccordionTrigger>
          <AccordionContent className="px-3">
            <Textarea
              {...control.register("description")}
              placeholder="Dashboard description"
              maxLength={255}
            />
          </AccordionContent>
        </AccordionItem>

        {showDataSourceSection && (
          <AccordionItem value="additional-settings">
            <AccordionTrigger className="h-11 py-1.5">
              Dashboard defaults
            </AccordionTrigger>
            <AccordionContent className="px-3 pb-3">
              <DashboardDataSourceSection />
            </AccordionContent>
          </AccordionItem>
        )}
      </Accordion>
    </div>
  );
};

export default DashboardDialogDetailsStep;
