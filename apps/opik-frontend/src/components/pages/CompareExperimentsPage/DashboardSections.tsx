import React, { useState, useMemo, useCallback } from "react";
import { ChevronDown, ChevronRight, Plus, Edit2, Trash2, MoreHorizontal } from "lucide-react";
import { useDashboardStore } from "./dashboardStore";
import { Panel, PanelSection, DashboardWithSections } from "./dashboardTypes";
import useDashboardSectionCreateMutation from "@/api/dashboards/useDashboardSectionCreateMutation";
import useDashboardPanelCreateMutation from "@/api/dashboards/useDashboardPanelCreateMutation";
import PanelGrid from "./PanelGrid";
import PanelModal from "./PanelModal";
import { Button } from "@/components/ui/button";

interface DashboardSectionsProps {
  experimentId: string;
  dashboard: DashboardWithSections;
}

const DashboardSections: React.FC<DashboardSectionsProps> = ({ experimentId, dashboard }) => {
  const { calculateNextPosition } = useDashboardStore();
  const [modalOpen, setModalOpen] = useState(false);
  const [modalSectionId, setModalSectionId] = useState<string>("");
  const [editingPanel, setEditingPanel] = useState<Panel | undefined>();
  const [expandedSections, setExpandedSections] = useState<Set<string>>(
    () => new Set(dashboard.sections.map(s => s.id))
  );

  // API mutations
  const createSectionMutation = useDashboardSectionCreateMutation();
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

  const handleSavePanel = useCallback(async (panel: Panel) => {
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
      </div>
    </div>
  ), [toggleSectionExpanded, handleAddPanel, createPanelMutation.isPending, modalSectionId]);

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
        section={section}
        onEditPanel={(panel) => handleEditPanel(section.id, panel)}
        onRemovePanel={(panelId) => {
          // TODO: Implement remove panel API call
          console.log("Remove panel:", panelId);
        }}
        onLayoutChange={(layout) => {
          // TODO: Implement layout change API call
          console.log("Layout changed:", layout);
        }}
      />
    );
  }, [experimentId, handleEditPanel, renderEmptySection]);

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
        {frontendSections.map((section: PanelSection) => (
          <div key={section.id} className="border rounded-lg bg-white shadow-sm hover:shadow-md transition-shadow mb-6 mx-6">
            {renderSectionHeader(section)}

            {section.isExpanded && (
              <div className="p-4">
                {renderSectionContent(section)}
              </div>
            )}
          </div>
        ))}
      </div>

      <PanelModal
        open={modalOpen}
        onClose={handleCloseModal}
        onSave={handleSavePanel}
        sectionId={modalSectionId}
        panel={editingPanel}
      />
    </div>
  );
};

export default React.memo(DashboardSections);
