import React, { useState, useMemo } from "react";
import { Search, Plus, Edit, Trash2, ExternalLink } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { useToast } from "@/components/ui/use-toast";
import Loader from "@/components/shared/Loader/Loader";
import useDashboardTemplates from "@/api/dashboardTemplates/useDashboardTemplates";
import useDashboardTemplateCreateMutation from "@/api/dashboardTemplates/useDashboardTemplateCreateMutation";
import useDashboardTemplateUpdateMutation from "@/api/dashboardTemplates/useDashboardTemplateUpdateMutation";
import useDashboardTemplateDeleteMutation from "@/api/dashboardTemplates/useDashboardTemplateDeleteMutation";
import { DashboardTemplate } from "@/api/dashboardTemplates/useDashboardTemplatesById";
import DashboardTemplateModal from "./DashboardTemplateModal";
import { DashboardTemplateConfiguration } from "./DashboardTemplateBuilder";

const DashboardTemplatesPage: React.FC = () => {
  const { toast } = useToast();

  // State for search
  const [searchQuery, setSearchQuery] = useState("");

  // State for modals
  const [modalOpen, setModalOpen] = useState(false);
  const [modalMode, setModalMode] = useState<"create" | "edit">("create");
  const [editingTemplate, setEditingTemplate] = useState<DashboardTemplate | null>(null);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [templateToDelete, setTemplateToDelete] = useState<DashboardTemplate | null>(null);

  // API hooks
  const { data: templates = [], isLoading } = useDashboardTemplates({ search: searchQuery });
  const createMutation = useDashboardTemplateCreateMutation();
  const updateMutation = useDashboardTemplateUpdateMutation();
  const deleteMutation = useDashboardTemplateDeleteMutation();

  // Filtered templates based on search
  const filteredTemplates = useMemo(() => {
    if (!searchQuery) return templates;
    const query = searchQuery.toLowerCase();
    return templates.filter(
      (template) =>
        template.name.toLowerCase().includes(query) ||
        (template.description && template.description.toLowerCase().includes(query))
    );
  }, [templates, searchQuery]);

  // Handle create template
  const handleCreateTemplate = () => {
    setModalMode("create");
    setEditingTemplate(null);
    setModalOpen(true);
  };

  // Handle edit template
  const handleEditTemplate = (template: DashboardTemplate) => {
    setModalMode("edit");
    setEditingTemplate(template);
    setModalOpen(true);
  };

  // Handle save template (create or update)
  const handleSaveTemplate = async (templateData: {
    name: string;
    description?: string;
    configuration: DashboardTemplateConfiguration;
  }) => {
    try {
      if (modalMode === "create") {
        await createMutation.mutateAsync({
          name: templateData.name,
          description: templateData.description,
          configuration: templateData.configuration,
        });
        
        toast({
          title: "Success",
          description: "Dashboard template created successfully",
        });
      } else if (editingTemplate) {
        await updateMutation.mutateAsync({
          dashboardTemplateId: editingTemplate.id,
          name: templateData.name,
          description: templateData.description,
          configuration: templateData.configuration,
        });
        
        toast({
          title: "Success",
          description: "Dashboard template updated successfully",
        });
      }
    } catch (error) {
      toast({
        title: "Error",
        description: `Failed to ${modalMode === "create" ? "create" : "update"} dashboard template`,
        variant: "destructive",
      });
      throw error; // Re-throw to prevent modal from closing
    }
  };

  // Handle delete template
  const handleDeleteTemplate = async () => {
    if (!templateToDelete) return;
    
    try {
      await deleteMutation.mutateAsync({
        dashboardTemplateId: templateToDelete.id,
      });

      toast({
        title: "Success",
        description: "Dashboard template deleted successfully",
      });
      
      setDeleteDialogOpen(false);
      setTemplateToDelete(null);
    } catch (error) {
      toast({
        title: "Error",
        description: "Failed to delete dashboard template",
        variant: "destructive",
      });
    }
  };

  // Get section and panel counts for display
  const getTemplateCounts = (template: DashboardTemplate) => {
    const sections = template.configuration?.sections || [];
    const totalPanels = sections.reduce((sum, section) => sum + (section.panels?.length || 0), 0);
    return { sections: sections.length, panels: totalPanels };
  };

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Loader />
      </div>
    );
  }

  return (
    <div className="p-6 max-w-7xl mx-auto">
      {/* Header */}
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="comet-title-l">Dashboard Templates</h1>
          <p className="text-muted-foreground mt-1">
            Create and manage reusable dashboard configurations with sections and panels
          </p>
        </div>

        <Button onClick={handleCreateTemplate}>
          <Plus className="h-4 w-4 mr-2" />
          Create Template
        </Button>
      </div>

      {/* Search */}
      <div className="flex items-center gap-4 mb-6">
        <div className="relative flex-1 max-w-sm">
          <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 text-muted-foreground h-4 w-4" />
          <Input
            placeholder="Search templates..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-10"
          />
        </div>
        <div className="text-sm text-muted-foreground">
          {filteredTemplates.length} template{filteredTemplates.length !== 1 ? 's' : ''}
        </div>
      </div>

      {/* Templates Grid */}
      {filteredTemplates.length === 0 ? (
        <div className="text-center py-12">
          <div className="text-muted-foreground mb-4">
            {searchQuery ? "No templates match your search" : "No dashboard templates found"}
          </div>
          {!searchQuery && (
            <Button onClick={handleCreateTemplate}>
              <Plus className="h-4 w-4 mr-2" />
              Create Your First Template
            </Button>
          )}
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {filteredTemplates.map((template) => {
            const counts = getTemplateCounts(template);
            return (
              <div
                key={template.id}
                className="border rounded-lg p-6 hover:shadow-md transition-shadow bg-white"
              >
                <div className="flex items-start justify-between mb-4">
                  <div className="flex-1">
                    <h3 className="font-semibold text-lg mb-2 line-clamp-2">
                      {template.name}
                    </h3>
                    {template.description && (
                      <p className="text-muted-foreground text-sm line-clamp-3 mb-3">
                        {template.description}
                      </p>
                    )}
                    <div className="flex items-center gap-2 mb-3">
                      <Badge variant="gray" className="text-xs">
                        {counts.sections} section{counts.sections !== 1 ? 's' : ''}
                      </Badge>
                      <Badge variant="gray" className="text-xs">
                        {counts.panels} panel{counts.panels !== 1 ? 's' : ''}
                      </Badge>
                    </div>
                  </div>
                </div>

                <div className="text-xs text-muted-foreground mb-4">
                  <div>Created by {template.created_by}</div>
                  <div>
                    {new Date(template.created_at).toLocaleDateString()}
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => handleEditTemplate(template)}
                    className="flex-1"
                  >
                    <Edit className="h-4 w-4 mr-2" />
                    Edit
                  </Button>

                  <Button 
                    size="sm" 
                    variant="outline" 
                    className="text-destructive"
                    onClick={() => {
                      setTemplateToDelete(template);
                      setDeleteDialogOpen(true);
                    }}
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* Dashboard Template Modal */}
      <DashboardTemplateModal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        onSave={handleSaveTemplate}
        template={editingTemplate || undefined}
        mode={modalMode}
        isLoading={createMutation.isPending || updateMutation.isPending}
      />

      {/* Delete Confirmation Dialog */}
      <AlertDialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete Dashboard Template</AlertDialogTitle>
            <AlertDialogDescription>
              Are you sure you want to delete "{templateToDelete?.name}"? This action cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel onClick={() => {
              setDeleteDialogOpen(false);
              setTemplateToDelete(null);
            }}>
              Cancel
            </AlertDialogCancel>
            <AlertDialogAction
              onClick={handleDeleteTemplate}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
};

export default DashboardTemplatesPage; 