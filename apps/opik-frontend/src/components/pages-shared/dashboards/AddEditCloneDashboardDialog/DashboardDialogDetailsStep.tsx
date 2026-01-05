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
}

interface DashboardDialogDetailsStepProps {
  control: Control<DashboardFormFields>;
  templateType?: string;
  showProjectSelect?: boolean;
  showExperimentsSelect?: boolean;
  onSubmit: () => void;
}

const DashboardDialogDetailsStep: React.FunctionComponent<
  DashboardDialogDetailsStepProps
> = ({
  control,
  templateType,
  showProjectSelect,
  showExperimentsSelect,
  onSubmit,
}) => {
  const template = templateType
    ? DASHBOARD_TEMPLATES[templateType as TEMPLATE_TYPE]
    : null;

  return (
    <div className="flex flex-col gap-4">
      {template && (
        <DashboardTemplateCard
          name={template.name}
          description={template.description}
          icon={template.icon}
          iconColor={template.iconColor}
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

      {showProjectSelect && (
        <FormField
          control={control}
          name="projectId"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, ["projectId"]);

            return (
              <FormItem>
                <FormLabel>Project</FormLabel>
                <FormControl>
                  <ProjectsSelectBox
                    value={field.value || ""}
                    onValueChange={field.onChange}
                    className={cn({
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            );
          }}
        />
      )}

      {showExperimentsSelect && (
        <FormField
          control={control}
          name="experimentIds"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, ["experimentIds"]);

            return (
              <FormItem>
                <FormLabel>Experiments</FormLabel>
                <FormControl>
                  <ExperimentsSelectBox
                    value={field.value || []}
                    onValueChange={field.onChange}
                    multiselect
                    className={cn({
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            );
          }}
        />
      )}

      <div className="flex flex-col gap-2 border-t border-border pt-2">
        <Accordion type="multiple" defaultValue={["description"]}>
          <AccordionItem value="description">
            <AccordionTrigger>Description</AccordionTrigger>
            <AccordionContent>
              <Textarea
                {...control.register("description")}
                placeholder="Dashboard description"
                maxLength={255}
              />
            </AccordionContent>
          </AccordionItem>
        </Accordion>
      </div>
    </div>
  );
};

export default DashboardDialogDetailsStep;
