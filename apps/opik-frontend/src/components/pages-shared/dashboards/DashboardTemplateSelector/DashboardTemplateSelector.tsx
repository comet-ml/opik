import React from "react";
import { ChartLine, LayoutDashboard, LayoutTemplate } from "lucide-react";
import isEmpty from "lodash/isEmpty";
import isNil from "lodash/isNil";
import map from "lodash/map";
import get from "lodash/get";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import {
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import {
  DASHBOARD_TEMPLATES,
  TEMPLATE_OPTIONS_ORDER,
} from "@/lib/dashboard/templates";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import { cn } from "@/lib/utils";
import { Control } from "react-hook-form";
import { DASHBOARD_CREATION_TYPE } from "@/types/dashboard";

interface DashboardFormFields {
  name: string;
  description?: string;
  creationType: DASHBOARD_CREATION_TYPE;
  templateId?: string;
  projectId?: string;
}

interface DashboardTemplateSelectorProps {
  control: Control<DashboardFormFields>;
  onCreationTypeChange?: (value: DASHBOARD_CREATION_TYPE) => void;
}

const DashboardTemplateSelector: React.FC<DashboardTemplateSelectorProps> = ({
  control,
  onCreationTypeChange,
}) => {
  const handleCreationTypeChange = (value: string) => {
    if (!isEmpty(value)) {
      onCreationTypeChange?.(value as DASHBOARD_CREATION_TYPE);
    }
  };

  const renderTemplateCard = (
    template: {
      id: string;
      title: string;
      description: string;
    },
    templateField: {
      value?: string;
      onChange: (value: string) => void;
    },
  ) => {
    const isSelected = get(templateField, "value") === get(template, "id");
    const templateId = get(template, "id", "");
    const templateTitle = get(template, "title", "");
    const templateDescription = get(template, "description", "");

    return (
      <button
        key={templateId}
        type="button"
        onClick={() => templateField.onChange(templateId)}
        className={cn(
          "flex flex-col items-start gap-2 rounded-md border p-4 text-left transition-all hover:border-primary hover:bg-muted",
          isSelected ? "border-primary bg-muted" : "border-border",
        )}
      >
        <div className="flex items-center gap-2">
          <ChartLine className="size-5 text-foreground" />
          <span className="comet-body-s-accented">{templateTitle}</span>
        </div>
        <span className="comet-body-s text-muted-slate">
          {templateDescription}
        </span>
      </button>
    );
  };

  return (
    <>
      <FormField
        control={control}
        name="creationType"
        render={({ field }) => {
          const currentValue = get(
            field,
            "value",
            DASHBOARD_CREATION_TYPE.EMPTY,
          );

          return (
            <FormItem className="pb-2">
              <FormLabel>Type</FormLabel>
              <FormControl>
                <ToggleGroup
                  type="single"
                  variant="ghost"
                  value={currentValue}
                  onValueChange={(value) => {
                    if (!isEmpty(value)) {
                      field.onChange(value);
                      handleCreationTypeChange(value);
                    }
                  }}
                  className="w-fit justify-start"
                >
                  <ToggleGroupItem
                    value={DASHBOARD_CREATION_TYPE.EMPTY}
                    aria-label="Empty dashboard"
                    className="gap-1.5"
                  >
                    <LayoutDashboard className="size-3.5" />
                    <span>Empty dashboard</span>
                  </ToggleGroupItem>
                  <ToggleGroupItem
                    value={DASHBOARD_CREATION_TYPE.TEMPLATE}
                    aria-label="From template"
                    className="gap-1.5"
                  >
                    <LayoutTemplate className="size-3.5" />
                    <span>From template</span>
                  </ToggleGroupItem>
                </ToggleGroup>
              </FormControl>
              <FormMessage />
            </FormItem>
          );
        }}
      />

      {/* Template grid (conditional, shows when "From template" selected) */}
      <FormField
        control={control}
        name="creationType"
        render={({ field }) => {
          const creationType = get(
            field,
            "value",
            DASHBOARD_CREATION_TYPE.EMPTY,
          );
          const isTemplateMode =
            creationType === DASHBOARD_CREATION_TYPE.TEMPLATE;

          if (!isTemplateMode) return <></>;

          return (
            <>
              <FormField
                control={control}
                name="templateId"
                render={({ field: templateField }) => (
                  <FormItem className="pb-2">
                    <FormLabel>Template</FormLabel>
                    <FormControl>
                      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
                        {map(TEMPLATE_OPTIONS_ORDER, (templateId) =>
                          renderTemplateCard(
                            DASHBOARD_TEMPLATES[templateId],
                            templateField,
                          ),
                        )}
                      </div>
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              {/* Project selector (conditional, only visible when template selected) */}
              <FormField
                control={control}
                name="projectId"
                render={({ field: projectField }) => {
                  const projectValue = get(projectField, "value", "");

                  return (
                    <FormItem className="pb-2">
                      <FormLabel>Project</FormLabel>
                      <FormControl>
                        <ProjectsSelectBox
                          value={isNil(projectValue) ? "" : projectValue}
                          onValueChange={projectField.onChange}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  );
                }}
              />
            </>
          );
        }}
      />
    </>
  );
};

export default DashboardTemplateSelector;
