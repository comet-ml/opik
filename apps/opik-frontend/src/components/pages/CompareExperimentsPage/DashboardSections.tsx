import React, { useState, useMemo, useCallback } from "react";
import { ChevronDown, ChevronRight, Plus, Edit2, Trash2, MoreHorizontal } from "lucide-react";
import { useDashboardStore } from "./dashboardStore";
import { Panel, PanelSection, DashboardWithSections } from "./dashboardTypes";
import useDashboardSectionCreateMutation from "@/api/dashboards/useDashboardSectionCreateMutation";
import useDashboardUpdateMutation from "@/api/dashboards/useDashboardUpdateMutation";
import useDashboardPanelCreateMutation from "@/api/dashboards/useDashboardPanelCreateMutation";
import PanelGrid from "./PanelGrid";
import PanelModal from "./PanelModal";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

interface DashboardSectionsProps {
  experimentId: string;
  contextExperimentId?: string; // For experiment context
  dashboard: DashboardWithSections;
}

const DashboardSections: React.FC<DashboardSectionsProps> = ({ experimentId, contextExperimentId, dashboard }) => {
  const { calculateNextPosition } = useDashboardStore();
  const [modalOpen, setModalOpen] = useState(false);
  const [modalSectionId, setModalSectionId] = useState<string>("");
  const [editingPanel, setEditingPanel] = useState<Panel | undefined>();
  const [expandedSections, setExpandedSections] = useState<Set<string>>(
    () => new Set(dashboard.sections.map(s => s.id))
  );
  const [editingSectionId, setEditingSectionId] = useState<string | null>(null);
  const [editingSectionTitle, setEditingSectionTitle] = useState<string>("");

  // API mutations
  const createSectionMutation = useDashboardSectionCreateMutation();
  const updateDashboardMutation = useDashboardUpdateMutation();
  const createPanelMutation = useDashboardPanelCreateMutation();

  // Memoized handlers
  const handleAddSection = useCallback(async () => {
    try {
      await createSectionMutation.mutateAsync({
        dashboardId: dashboard.id,
        section: {
          title: `Section ${dashboard.sections.length + 1}`,
          position_order: dashboard.sections.length,
        }
      });
    } catch (error: any) {
      console.error("Failed to create section:", error);
    }
  }, [createSectionMutation, dashboard.id, dashboard.sections.length]);

  const handleEditSection = useCallback(async (sectionId: string, newTitle: string) => {
    try {
      await updateDashboardMutation.mutateAsync({
        dashboardId: dashboard.id,
        dashboard: {
          sections: dashboard.sections.map(section =>
            section.id === sectionId 
              ? { 
                  ...section, 
                  title: newTitle,
                  // Ensure we include all the required fields
                  panels: section.panels || []
                } 
              : {
                  ...section,
                  panels: section.panels || []
                }
          )
        }
      });
    } catch (error: any) {
      console.error("Failed to update section:", error);
    }
  }, [updateDashboardMutation, dashboard.id, dashboard.sections]);

  const handleRemoveSection = useCallback(async (sectionId: string) => {
    try {
      await updateDashboardMutation.mutateAsync({
        dashboardId: dashboard.id,
        dashboard: {
          sections: dashboard.sections
            .filter(section => section.id !== sectionId)
            .map(section => ({
              ...section,
              panels: section.panels || []
            }))
        }
      });
    } catch (error: any) {
      console.error("Failed to delete section:", error);
    }
  }, [updateDashboardMutation, dashboard.id, dashboard.sections]);

  const handleRemovePanel = useCallback(async (panelId: string) => {
    try {
      await updateDashboardMutation.mutateAsync({
        dashboardId: dashboard.id,
        dashboard: {
          sections: dashboard.sections.map(section => ({
            ...section,
            panels: (section.panels || []).filter(panel => panel.id !== panelId)
          }))
        }
      });
    } catch (error: any) {
      console.error("Failed to delete panel:", error);
    }
  }, [updateDashboardMutation, dashboard.id, dashboard.sections]);

  const handleAddPanel = useCallback((sectionId: string) => {
    setModalSectionId(sectionId);
    setEditingPanel(undefined);
    setModalOpen(true);
  }, []);

  const handleEditPanel = useCallback((sectionId: string, panel: Panel) => {
    setModalSectionId(sectionId);
    setEditingPanel(panel);
    setModalOpen(true);
  }, []);

  const handleCloseModal = useCallback(() => {
    setModalOpen(false);
    setEditingPanel(undefined);
  }, []);

  // Convert backend sections to frontend format for display - memoized
  const frontendSections = useMemo((): PanelSection[] => {
    return dashboard.sections.map(section => ({
      id: section.id,
      title: section.title,
      isExpanded: expandedSections.has(section.id),
      items: section.panels.map(panel => ({
        id: panel.id,
        name: panel.name,
        data: {
          type: panel.type.toLowerCase() as any,
          config: panel.configuration || {}
        }
      })),
      layout: section.panels.map(panel => ({
        i: panel.id,
        x: panel.layout.x,
        y: panel.layout.y,
        w: panel.layout.w,
        h: panel.layout.h
      }))
    }));
  }, [dashboard.sections, expandedSections]);

  const handleSavePanel = useCallback(async (panel: Panel, templateId?: string) => {
    try {
      // Find the section to get its layout for positioning
      const section = frontendSections.find(s => s.id === modalSectionId);
      const nextPosition = section ? calculateNextPosition(section.layout) : { x: 0, y: 0, w: 6, h: 4 };

      await createPanelMutation.mutateAsync({
        dashboardId: dashboard.id,
        sectionId: modalSectionId,
                  panel: {
          name: panel.name,
          type: panel.data.type.toUpperCase() as any,
          configuration: panel.data.config,
          layout: nextPosition,
          templateId: templateId,
        }
      });
      
      handleCloseModal();
    } catch (error: any) {
      console.error("Failed to create panel:", error);
    }
  }, [
    frontendSections, 
    modalSectionId, 
    calculateNextPosition, 
    createPanelMutation, 
    dashboard.id, 
    handleCloseModal
  ]);

  const toggleSectionExpanded = useCallback((sectionId: string) => {
    setExpandedSections(prev => {
      const newSet = new Set(prev);
      if (newSet.has(sectionId)) {
        newSet.delete(sectionId);
      } else {
        newSet.add(sectionId);
      }
      return newSet;
    });
  }, []);

  // Memoized section renderers
  const renderSectionHeader = useCallback((section: PanelSection) => (
    <div className="flex items-center justify-between p-4 border-b bg-white rounded-t-lg">
      <div className="flex items-center gap-2">
        <button
          onClick={() => toggleSectionExpanded(section.id)}
          className="p-1 hover:bg-muted/50 rounded transition-colors"
        >
          {section.isExpanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
        </button>
        
        <div className="flex items-center gap-2">
          <h3 className="comet-title-m">{section.title}</h3>
          <span className="text-xs text-gray-500 bg-gray-200 px-2 py-1 rounded">
            {section.items.length} panel{section.items.length !== 1 ? 's' : ''}
          </span>
        </div>
      </div>

      <div className="flex items-center gap-2">
        {section.isExpanded && (
          <Button
            onClick={() => handleAddPanel(section.id)}
            variant="outline"
            size="sm"
            className="flex items-center gap-1"
            disabled={createPanelMutation.isPending}
          >
            <Plus size={14} />
            {createPanelMutation.isPending && modalSectionId === section.id ? "Adding..." : "Add Panel"}
          </Button>
        )}
        
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button size="sm" variant="ghost">
              <MoreHorizontal size={16} />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent>
            <DropdownMenuItem onClick={() => {
              setEditingSectionTitle(section.title);
              setEditingSectionId(section.id);
            }}>
              <Edit2 size={14} className="mr-2" />
              Edit Section
            </DropdownMenuItem>
            <DropdownMenuItem 
              onClick={() => handleRemoveSection(section.id)}
              className="text-destructive"
              disabled={updateDashboardMutation.isPending}
            >
              <Trash2 size={14} className="mr-2" />
              {updateDashboardMutation.isPending ? "Removing..." : "Remove Section"}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </div>
  ), [toggleSectionExpanded, handleAddPanel, createPanelMutation.isPending, modalSectionId, handleRemoveSection, updateDashboardMutation.isPending]);

  const renderEmptySection = useCallback((section: PanelSection) => (
    <div className="text-center py-8 text-gray-500">
      <div className="text-4xl mb-2">📊</div>
      <p>No panels in this section yet.</p>
      <Button
        onClick={() => handleAddPanel(section.id)}
        variant="outline"
        className="mt-4"
        disabled={createPanelMutation.isPending}
      >
        {createPanelMutation.isPending && modalSectionId === section.id ? "Adding..." : "Add First Panel"}
      </Button>
    </div>
  ), [handleAddPanel, createPanelMutation.isPending, modalSectionId]);

  const renderSectionContent = useCallback((section: PanelSection) => {
    if (section.items.length === 0) {
      return renderEmptySection(section);
    }

    return (
      <PanelGrid
        experimentId={experimentId}
        contextExperimentId={contextExperimentId}
        section={section}
        onEditPanel={(panel) => handleEditPanel(section.id, panel)}
        onRemovePanel={handleRemovePanel}
        onLayoutChange={(layout) => {
          // TODO: Implement layout change API call
          console.log("Layout changed:", layout);
        }}
      />
    );
  }, [experimentId, contextExperimentId, handleEditPanel, handleRemovePanel, renderEmptySection]);

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between px-6 py-6 border-b bg-background">
        <div>
          <h2 className="comet-title-l">{dashboard.name}</h2>
          <p className="text-sm text-gray-600 mt-1">
            {dashboard.description || "Organize your panels into collapsible sections"}
          </p>
        </div>
        <Button
          onClick={handleAddSection}
          variant="outline"
          size="sm"
          className="flex items-center gap-2"
          disabled={createSectionMutation.isPending}
        >
          <Plus size={16} />
          {createSectionMutation.isPending ? "Adding..." : "Add Section"}
        </Button>
      </div>

      <div>
        {frontendSections.length === 0 ? (
          <div className="text-center py-12 mx-6">
            <div className="text-6xl mb-4">📊</div>
            <h3 className="comet-title-m mb-2">Get Started with Dashboard Sections</h3>
            <p className="text-muted-foreground mb-4">
              This dashboard doesn't have any sections yet. Add your first section to organize your panels and data visualizations.
            </p>
            <Button onClick={handleAddSection} size="lg" disabled={createSectionMutation.isPending}>
              <Plus className="mr-2 size-4" />
              {createSectionMutation.isPending ? "Adding..." : "Add First Section"}
            </Button>
          </div>
        ) : (
          frontendSections.map((section: PanelSection) => (
            <div key={section.id} className="border rounded-lg bg-white shadow-sm hover:shadow-md transition-shadow mb-6 mx-6">
              {renderSectionHeader(section)}

              {section.isExpanded && (
                <div className="p-4">
                  {renderSectionContent(section)}
                </div>
              )}
            </div>
          ))
        )}
      </div>

      <PanelModal
        open={modalOpen}
        onClose={handleCloseModal}
        onSave={handleSavePanel}
        sectionId={modalSectionId}
        panel={editingPanel}
        contextExperimentId={contextExperimentId}
      />

      {/* Section Edit Dialog */}
      <Dialog open={!!editingSectionId} onOpenChange={(open) => {
        if (!open) {
          setEditingSectionId(null);
          setEditingSectionTitle("");
        }
      }}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Edit Section Title</DialogTitle>
          </DialogHeader>
          <div className="grid gap-4 py-4">
            <div className="space-y-2">
              <Label htmlFor="section-title">
                Section Title
              </Label>
              <Input
                id="section-title"
                value={editingSectionTitle}
                onChange={(e) => setEditingSectionTitle(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && editingSectionId && editingSectionTitle.trim()) {
                    handleEditSection(editingSectionId, editingSectionTitle.trim());
                    setEditingSectionId(null);
                    setEditingSectionTitle("");
                  }
                }}
                autoFocus
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => {
              setEditingSectionId(null);
              setEditingSectionTitle("");
            }}>
              Cancel
            </Button>
            <Button 
              onClick={() => {
                if (editingSectionId && editingSectionTitle.trim()) {
                  handleEditSection(editingSectionId, editingSectionTitle.trim());
                  setEditingSectionId(null);
                  setEditingSectionTitle("");
                }
              }}
              disabled={updateDashboardMutation.isPending}
            >
              {updateDashboardMutation.isPending ? "Saving..." : "Save Changes"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
};

export default React.memo(DashboardSections);
