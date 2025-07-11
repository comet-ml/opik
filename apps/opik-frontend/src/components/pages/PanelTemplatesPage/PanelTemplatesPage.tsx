import React, { useState, useMemo, useCallback } from "react";
import { Plus, Search, Filter, Edit2, Trash2, Copy } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog";
import usePanelTemplates from "@/api/panelTemplates/usePanelTemplates";
import usePanelTemplateCreateMutation from "@/api/panelTemplates/usePanelTemplateCreateMutation";
import usePanelTemplateUpdateMutation from "@/api/panelTemplates/usePanelTemplateUpdateMutation";
import usePanelTemplateDeleteMutation from "@/api/panelTemplates/usePanelTemplateDeleteMutation";
import { ReusablePanelTemplate } from "@/api/panelTemplates/usePanelTemplatesById";
import { useToast } from "@/components/ui/use-toast";
import PanelModal from "@/components/pages/CompareExperimentsPage/PanelModal";
import { Panel } from "@/components/pages/CompareExperimentsPage/dashboardTypes";

const PANEL_TYPE_COLORS: Record<string, string> = {
  PYTHON: "bg-blue-100 text-blue-800",
  CHART: "bg-green-100 text-green-800", 
  TEXT: "bg-gray-100 text-gray-800",
  METRIC: "bg-orange-100 text-orange-800",
  HTML: "bg-purple-100 text-purple-800",
};

const PanelTemplatesPage: React.FC = () => {
  const { toast } = useToast();
  const [searchTerm, setSearchTerm] = useState("");
  const [typeFilter, setTypeFilter] = useState<string>("");
  const [deleteTemplateId, setDeleteTemplateId] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingTemplate, setEditingTemplate] = useState<ReusablePanelTemplate | null>(null);
  
  // API hooks
  const { data: templates = [], isLoading, error } = usePanelTemplates({
    type: typeFilter as any
  });
  const createTemplateMutation = usePanelTemplateCreateMutation();
  const updateTemplateMutation = usePanelTemplateUpdateMutation();
  const deleteTemplateMutation = usePanelTemplateDeleteMutation();

  // Check if we're in edit mode
  const isEditMode = useMemo(() => !!editingTemplate, [editingTemplate]);

  // Filtered templates based on search
  const filteredTemplates = useMemo(() => {
    return templates.filter(template => 
      template.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
      (template.description?.toLowerCase().includes(searchTerm.toLowerCase()) ?? false)
    );
  }, [templates, searchTerm]);

  // Convert template to Panel format for the modal
  const templateToPanel = useCallback((template: ReusablePanelTemplate): Panel => {
    return {
      id: template.id,
      name: template.name,
      data: {
        type: template.type.toLowerCase() as any,
        config: template.configuration || {}
      }
    };
  }, []);

  // Handlers
  const handleSearch = useCallback((value: string) => {
    setSearchTerm(value);
  }, []);

  const handleTypeFilter = useCallback((value: string) => {
    setTypeFilter(value === "all" ? "" : value);
  }, []);

  const handleCreateTemplate = useCallback(() => {
    setEditingTemplate(null);
    setModalOpen(true);
  }, []);

  const handleEditTemplate = useCallback((templateId: string) => {
    const template = templates.find(t => t.id === templateId);
    if (template) {
      setEditingTemplate(template);
      setModalOpen(true);
    }
  }, [templates]);

  const handleModalClose = useCallback(() => {
    setModalOpen(false);
    setEditingTemplate(null);
  }, []);

  const handleSaveTemplate = useCallback(async (panel: Panel) => {
    try {
      if (isEditMode && editingTemplate) {
        // Update existing template
        await updateTemplateMutation.mutateAsync({
          templateId: editingTemplate.id,
          template: {
            name: panel.name,
            description: `Template for ${panel.data.type} panel`,
            type: panel.data.type.toUpperCase() as any,
            configuration: panel.data.config,
            default_layout: { x: 0, y: 0, w: 6, h: 4 },
          }
        });

        toast({
          description: "Panel template updated successfully",
        });
      } else {
        // Create new template
        await createTemplateMutation.mutateAsync({
          template: {
            name: panel.name,
            description: `Template for ${panel.data.type} panel`,
            type: panel.data.type.toUpperCase() as any,
            configuration: panel.data.config,
            default_layout: { x: 0, y: 0, w: 6, h: 4 },
          }
        });

        toast({
          description: "Panel template created successfully",
        });
      }

      handleModalClose();
    } catch (error) {
      console.error(`Failed to ${isEditMode ? 'update' : 'create'} template:`, error);
      toast({
        description: `Failed to ${isEditMode ? 'update' : 'create'} panel template`,
        variant: "destructive",
      });
    }
  }, [isEditMode, editingTemplate, createTemplateMutation, updateTemplateMutation, toast, handleModalClose]);

  const handleDeleteConfirm = useCallback(async () => {
    if (!deleteTemplateId) return;
    
    try {
      await deleteTemplateMutation.mutateAsync({ templateId: deleteTemplateId });
      setDeleteTemplateId(null);
      toast({
        description: "Panel template deleted successfully",
      });
    } catch (error) {
      console.error("Failed to delete template:", error);
      toast({
        description: "Failed to delete panel template",
        variant: "destructive",
      });
    }
  }, [deleteTemplateId, deleteTemplateMutation, toast]);

  const handleCopyTemplate = useCallback((template: ReusablePanelTemplate) => {
    const templateData = {
      name: `${template.name} (Copy)`,
      type: template.type,
      configuration: template.configuration,
      defaultLayout: template.default_layout
    };
    
    navigator.clipboard.writeText(JSON.stringify(templateData, null, 2));
    toast({
      description: "Template configuration copied to clipboard",
    });
  }, [toast]);

  if (error) {
    return (
      <div className="flex items-center justify-center h-96">
        <div className="text-center">
          <h3 className="text-lg font-semibold text-red-600">Error loading panel templates</h3>
          <p className="text-sm text-muted-foreground mt-1">Please try refreshing the page</p>
        </div>
      </div>
    );
  }

  return (
    <div className="container mx-auto px-6 py-8">
      {/* Header */}
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold">Panel Templates</h1>
          <p className="text-muted-foreground mt-1">
            Create and manage reusable panel templates for your dashboards
          </p>
        </div>
        <Button size="sm" className="flex items-center gap-2" onClick={handleCreateTemplate}>
          <Plus size={16} />
          Create Template
        </Button>
      </div>

      {/* Filters */}
      <div className="flex items-center gap-4 mb-6">
        <div className="relative flex-1 max-w-md">
          <Search size={16} className="absolute left-3 top-1/2 transform -translate-y-1/2 text-muted-foreground" />
          <Input
            placeholder="Search templates..."
            value={searchTerm}
            onChange={(e) => handleSearch(e.target.value)}
            className="pl-10"
          />
        </div>
        
        <Select value={typeFilter || "all"} onValueChange={handleTypeFilter}>
          <SelectTrigger className="w-48">
            <Filter size={16} className="mr-2" />
            <SelectValue placeholder="Filter by type" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">All Types</SelectItem>
            <SelectItem value="PYTHON">Python</SelectItem>
            <SelectItem value="CHART">Chart</SelectItem>
            <SelectItem value="TEXT">Text</SelectItem>
            <SelectItem value="METRIC">Metric</SelectItem>
            <SelectItem value="HTML">HTML</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* Templates Grid */}
      {isLoading ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {Array.from({ length: 6 }).map((_, i) => (
            <Card key={i} className="animate-pulse">
              <CardHeader>
                <div className="h-4 bg-gray-200 rounded w-3/4 mb-2" />
                <div className="h-3 bg-gray-200 rounded w-1/2" />
              </CardHeader>
              <CardContent>
                <div className="h-16 bg-gray-200 rounded mb-4" />
                <div className="flex justify-between items-center">
                  <div className="h-6 bg-gray-200 rounded w-16" />
                  <div className="flex gap-2">
                    <div className="h-8 bg-gray-200 rounded w-8" />
                    <div className="h-8 bg-gray-200 rounded w-8" />
                    <div className="h-8 bg-gray-200 rounded w-8" />
                  </div>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      ) : filteredTemplates.length === 0 ? (
        <div className="text-center py-12">
          <div className="text-4xl mb-4">📊</div>
          <h3 className="text-lg font-semibold mb-2">No panel templates found</h3>
          <p className="text-muted-foreground mb-4">
            {searchTerm || typeFilter 
              ? "Try adjusting your search or filter criteria" 
              : "Create your first reusable panel template to get started"
            }
          </p>
          <Button size="sm" onClick={handleCreateTemplate}>
            <Plus size={16} className="mr-2" />
            Create Template
          </Button>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {filteredTemplates.map((template) => (
            <Card key={template.id} className="hover:shadow-md transition-shadow">
              <CardHeader>
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <CardTitle className="text-lg">{template.name}</CardTitle>
                    <CardDescription className="mt-1">
                      {template.description || "No description provided"}
                    </CardDescription>
                  </div>
                  <Badge className={`${PANEL_TYPE_COLORS[template.type]} border-0`}>
                    {template.type}
                  </Badge>
                </div>
              </CardHeader>
              <CardContent>
                <div className="flex items-center justify-between text-sm text-muted-foreground mb-4">
                  <span>Created by {template.created_by}</span>
                  <span>{new Date(template.created_at).toLocaleDateString()}</span>
                </div>
                
                <div className="flex items-center justify-end gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => handleCopyTemplate(template)}
                    title="Copy configuration"
                  >
                    <Copy size={14} />
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => handleEditTemplate(template.id)}
                    title="Edit template"
                  >
                    <Edit2 size={14} />
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setDeleteTemplateId(template.id)}
                    title="Delete template"
                    className="text-red-600 hover:text-red-700"
                  >
                    <Trash2 size={14} />
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {/* Panel Modal - Supports both Create and Edit modes */}
      <PanelModal
        open={modalOpen}
        onClose={handleModalClose}
        onSave={handleSaveTemplate}
        sectionId="" // Not needed for templates
        panel={editingTemplate ? templateToPanel(editingTemplate) : undefined}
      />

      {/* Delete Confirmation Dialog */}
      <AlertDialog open={!!deleteTemplateId} onOpenChange={() => setDeleteTemplateId(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete Panel Template</AlertDialogTitle>
            <AlertDialogDescription>
              This action cannot be undone. This will permanently delete the panel template.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleDeleteConfirm}
              className="bg-red-600 hover:bg-red-700"
              disabled={deleteTemplateMutation.isPending}
            >
              {deleteTemplateMutation.isPending ? "Deleting..." : "Delete"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
};

export default PanelTemplatesPage; 