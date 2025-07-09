import React, { useState, useEffect } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { X, Save, Eye, EyeOff } from "lucide-react";
import { Button } from "@/components/ui/button";
import { 
  Dialog, 
  DialogContent, 
  DialogHeader, 
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import DashboardTemplateBuilder, { DashboardTemplateConfiguration } from "./DashboardTemplateBuilder";
import { DashboardTemplate } from "@/api/dashboardTemplates/useDashboardTemplatesById";

const dashboardTemplateFormSchema = z.object({
  name: z
    .string()
    .min(1, "Template name is required")
    .max(100, "Template name must be less than 100 characters"),
  description: z
    .string()
    .max(500, "Description must be less than 500 characters")
    .optional(),
});

type DashboardTemplateFormData = z.infer<typeof dashboardTemplateFormSchema>;

interface DashboardTemplateModalProps {
  open: boolean;
  onClose: () => void;
  onSave: (template: {
    name: string;
    description?: string;
    configuration: DashboardTemplateConfiguration;
  }) => Promise<void>;
  template?: DashboardTemplate;
  mode: "create" | "edit";
  isLoading?: boolean;
}

const DashboardTemplateModal: React.FC<DashboardTemplateModalProps> = ({
  open,
  onClose,
  onSave,
  template,
  mode,
  isLoading = false,
}) => {
  const [activeTab, setActiveTab] = useState("details");
  const [configuration, setConfiguration] = useState<DashboardTemplateConfiguration>({ sections: [] });
  const [previewMode, setPreviewMode] = useState(false);

  const form = useForm<DashboardTemplateFormData>({
    resolver: zodResolver(dashboardTemplateFormSchema),
    defaultValues: {
      name: "",
      description: "",
    },
  });

  // Initialize form and configuration when template changes
  useEffect(() => {
    if (template && mode === "edit") {
      form.reset({
        name: template.name,
        description: template.description || "",
      });
      
      // Parse existing configuration from template
      try {
        const config = typeof template.configuration === 'string' 
          ? JSON.parse(template.configuration)
          : template.configuration;
        setConfiguration(config || { sections: [] });
      } catch (error) {
        console.warn("Failed to parse template configuration:", error);
        setConfiguration({ sections: [] });
      }
    } else {
      // Reset for create mode
      form.reset({
        name: "",
        description: "",
      });
      setConfiguration({ sections: [] });
    }
  }, [template, mode, form]);

  // Reset state when modal opens/closes
  useEffect(() => {
    if (!open) {
      setActiveTab("details");
      setPreviewMode(false);
    }
  }, [open]);

  const handleSave = async (data: DashboardTemplateFormData) => {
    try {
      await onSave({
        name: data.name.trim(),
        description: data.description?.trim(),
        configuration,
      });
      onClose();
    } catch (error) {
      console.error("Failed to save dashboard template:", error);
    }
  };

  const handleConfigurationChange = (newConfiguration: DashboardTemplateConfiguration) => {
    setConfiguration(newConfiguration);
  };

  const canSave = () => {
    const formData = form.getValues();
    return formData.name.trim().length > 0;
  };

  const getSectionCount = () => configuration.sections.length;
  const getPanelCount = () => configuration.sections.reduce((total, section) => total + section.panels.length, 0);

  return (
    <Dialog open={open} onOpenChange={onClose}>
      <DialogContent className="max-w-7xl max-h-[90vh] overflow-hidden flex flex-col">
        <DialogHeader className="flex-shrink-0">
          <div className="flex items-center justify-between">
            <DialogTitle>
              {mode === "create" ? "Create Dashboard Template" : "Edit Dashboard Template"}
            </DialogTitle>
            <div className="flex items-center gap-2">
              {activeTab === "builder" && (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setPreviewMode(!previewMode)}
                >
                  {previewMode ? <EyeOff className="mr-2 size-4" /> : <Eye className="mr-2 size-4" />}
                  {previewMode ? "Edit" : "Preview"}
                </Button>
              )}
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={onClose}
              >
                <X className="size-4" />
              </Button>
            </div>
          </div>
          <p className="text-sm text-muted-foreground">
            {mode === "create" 
              ? "Create a reusable dashboard template with sections and panels"
              : "Modify your dashboard template structure and configuration"
            }
          </p>
        </DialogHeader>

        <Tabs value={activeTab} onValueChange={setActiveTab} className="flex-1 flex flex-col overflow-hidden">
          <TabsList className="grid w-full grid-cols-2 flex-shrink-0">
            <TabsTrigger value="details">Template Details</TabsTrigger>
            <TabsTrigger value="builder" className="flex items-center gap-2">
              Dashboard Builder
              {(getSectionCount() > 0 || getPanelCount() > 0) && (
                <span className="text-xs bg-primary text-primary-foreground rounded-full px-2 py-0.5">
                  {getSectionCount()}s, {getPanelCount()}p
                </span>
              )}
            </TabsTrigger>
          </TabsList>

          <div className="flex-1 overflow-hidden">
            <TabsContent value="details" className="h-full overflow-auto">
              <div className="p-6 space-y-6">
                <Form {...form}>
                  <div className="space-y-4">
                    <FormField
                      control={form.control}
                      name="name"
                      render={({ field }) => (
                        <FormItem>
                          <FormLabel>Template Name *</FormLabel>
                          <FormControl>
                            <Input 
                              placeholder="Enter template name..."
                              {...field}
                            />
                          </FormControl>
                          <FormMessage />
                        </FormItem>
                      )}
                    />

                    <FormField
                      control={form.control}
                      name="description"
                      render={({ field }) => (
                        <FormItem>
                          <FormLabel>Description</FormLabel>
                          <FormControl>
                            <Textarea 
                              placeholder="Describe what this template is for..."
                              className="min-h-[100px]"
                              {...field}
                            />
                          </FormControl>
                          <FormMessage />
                        </FormItem>
                      )}
                    />
                  </div>
                </Form>

                <div className="border-t pt-6">
                  <h4 className="font-medium mb-4">Template Structure</h4>
                  <div className="grid grid-cols-2 gap-4 text-sm">
                    <div className="bg-muted p-3 rounded">
                      <div className="font-medium text-muted-foreground">Sections</div>
                      <div className="text-2xl font-bold">{getSectionCount()}</div>
                    </div>
                    <div className="bg-muted p-3 rounded">
                      <div className="font-medium text-muted-foreground">Panels</div>
                      <div className="text-2xl font-bold">{getPanelCount()}</div>
                    </div>
                  </div>
                  {getSectionCount() > 0 && (
                    <div className="mt-4">
                      <h5 className="font-medium mb-2">Sections Overview</h5>
                      <div className="space-y-2">
                        {configuration.sections.map((section, index) => (
                          <div key={section.id} className="flex justify-between items-center py-2 px-3 bg-muted/50 rounded">
                            <span className="font-medium">{section.title}</span>
                            <span className="text-sm text-muted-foreground">
                              {section.panels.length} panel{section.panels.length !== 1 ? 's' : ''}
                            </span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              </div>
            </TabsContent>

            <TabsContent value="builder" className="h-full overflow-hidden">
              <div className="h-full overflow-auto">
                <DashboardTemplateBuilder
                  initialConfiguration={configuration}
                  onConfigurationChange={handleConfigurationChange}
                  readonly={previewMode}
                />
              </div>
            </TabsContent>
          </div>
        </Tabs>

        <div className="flex-shrink-0 border-t p-6">
          <div className="flex items-center justify-between">
            <div className="text-sm text-muted-foreground">
              {getSectionCount() > 0 && (
                <span>
                  Template contains {getSectionCount()} section{getSectionCount() !== 1 ? 's' : ''} 
                  {getPanelCount() > 0 && (
                    <> with {getPanelCount()} panel{getPanelCount() !== 1 ? 's' : ''}</>
                  )}
                </span>
              )}
            </div>
            <div className="flex gap-3">
              <Button
                type="button"
                variant="outline"
                onClick={onClose}
                disabled={isLoading}
              >
                Cancel
              </Button>
              <Button
                type="button"
                onClick={form.handleSubmit(handleSave)}
                disabled={!canSave() || isLoading}
              >
                {isLoading ? (
                  <>
                    <div className="mr-2 size-4 animate-spin rounded-full border-2 border-background border-t-transparent" />
                    Saving...
                  </>
                ) : (
                  <>
                    <Save className="mr-2 size-4" />
                    {mode === "create" ? "Create Template" : "Save Changes"}
                  </>
                )}
              </Button>
            </div>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default DashboardTemplateModal; 