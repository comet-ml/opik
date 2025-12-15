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

interface DashboardFormFields {
  name: string;
  description?: string;
  projectId?: string;
  experimentIds?: string[];
}

interface DashboardDialogDetailsStepProps {
  control: Control<DashboardFormFields>;
  showProjectSelect?: boolean;
  showExperimentsSelect?: boolean;
  onSubmit: () => void;
}

const DashboardDialogDetailsStep: React.FunctionComponent<
  DashboardDialogDetailsStepProps
> = ({ control, showProjectSelect, showExperimentsSelect, onSubmit }) => {
  return (
    <div className="flex flex-col gap-4">
      {/* Name field */}
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

      {/* Project selector (conditional) */}
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

      {/* Experiments selector (conditional) */}
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
