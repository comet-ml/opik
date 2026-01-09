import React from "react";
import get from "lodash/get";
import {
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Description } from "@/components/ui/description";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import ExperimentsSelectBox from "@/components/pages-shared/experiments/ExperimentsSelectBox/ExperimentsSelectBox";
import { Control } from "react-hook-form";
import { cn } from "@/lib/utils";
import { DASHBOARD_TEMPLATES } from "@/lib/dashboard/templates";
import { TEMPLATE_TYPE } from "@/types/dashboard";
import DashboardTemplateCard from "./DashboardTemplateCard";

interface DashboardFormFields {
  name: string;
  description?: string;
  projectId?: string;
  experimentIds?: string[];
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
      </Accordion>

      {showDataSourceSection && (
        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-1">
            <h4 className="comet-body-accented">Default data source</h4>
            <Description>
              Choose default project and experiments to preview data in this
              dashboard. Individual widgets can override these settings if
              needed.
            </Description>
          </div>

          <FormField
            control={control}
            name="experimentIds"
            render={({ field, formState }) => {
              const validationErrors = get(formState.errors, ["experimentIds"]);

              return (
                <FormItem>
                  <FormLabel>Default experiments (optional)</FormLabel>
                  <FormControl>
                    <ExperimentsSelectBox
                      value={field.value || []}
                      onValueChange={field.onChange}
                      multiselect
                      showClearButton
                      className={cn("flex-1", {
                        "border-destructive": Boolean(
                          validationErrors?.message,
                        ),
                      })}
                    />
                  </FormControl>
                  <Description>
                    Used by widgets that show experiment data.
                  </Description>
                  <FormMessage />
                </FormItem>
              );
            }}
          />

          <FormField
            control={control}
            name="projectId"
            render={({ field, formState }) => {
              const validationErrors = get(formState.errors, ["projectId"]);

              return (
                <FormItem>
                  <FormLabel>Default project (optional)</FormLabel>
                  <FormControl>
                    <ProjectsSelectBox
                      value={field.value || ""}
                      onValueChange={field.onChange}
                      showClearButton
                      className={cn("flex-1", {
                        "border-destructive": Boolean(
                          validationErrors?.message,
                        ),
                      })}
                    />
                  </FormControl>
                  <Description>Used to preview data by default.</Description>
                  <FormMessage />
                </FormItem>
              );
            }}
          />
        </div>
      )}
    </div>
  );
};

export default DashboardDialogDetailsStep;
