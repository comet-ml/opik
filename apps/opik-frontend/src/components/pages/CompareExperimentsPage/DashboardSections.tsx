import React, { useState } from "react";
import { ChevronDown, ChevronRight, Plus, Edit2, Trash2, MoreHorizontal } from "lucide-react";
import { useDashboardStore } from "./dashboardStore";
import { Panel, PanelSection, DashboardWithSections } from "./dashboardTypes";
import useDashboardSectionCreateMutation from "@/api/dashboards/useDashboardSectionCreateMutation";
import useDashboardPanelCreateMutation from "@/api/dashboards/useDashboardPanelCreateMutation";
import PanelGrid from "./PanelGrid";
import PanelModal from "./PanelModal";

interface DashboardSectionsProps {
  experimentId: string;
  dashboard: DashboardWithSections;
}

const DashboardSections: React.FC<DashboardSectionsProps> = ({ experimentId, dashboard }) => {
  const { calculateNextPosition } = useDashboardStore();
  const [modalOpen, setModalOpen] = useState(false);
  const [modalSectionId, setModalSectionId] = useState<string>("");
  const [editingPanel, setEditingPanel] = useState<Panel | undefined>();
  const [expandedSections, setExpandedSections] = useState<Set<string>>(new Set(dashboard.sections.map(s => s.id)));

  // API mutations
  const createSectionMutation = useDashboardSectionCreateMutation();
  const createPanelMutation = useDashboardPanelCreateMutation();

  const handleAddSection = async () => {
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
  };

  const handleAddPanel = (sectionId: string) => {
    setModalSectionId(sectionId);
    setEditingPanel(undefined);
    setModalOpen(true);
  };

  const handleEditPanel = (sectionId: string, panel: Panel) => {
    setModalSectionId(sectionId);
    setEditingPanel(panel);
    setModalOpen(true);
  };

  const handleSavePanel = async (panel: Panel) => {
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
      
      setModalOpen(false);
      setEditingPanel(undefined);
    } catch (error: any) {
      console.error("Failed to create panel:", error);
    }
  };

  const toggleSectionExpanded = (sectionId: string) => {
    setExpandedSections(prev => {
      const newSet = new Set(prev);
      if (newSet.has(sectionId)) {
        newSet.delete(sectionId);
      } else {
        newSet.add(sectionId);
      }
      return newSet;
    });
  };

  // Convert backend sections to frontend format for display
  const convertToFrontendSections = (backendSections: DashboardWithSections['sections']): PanelSection[] => {
    return backendSections.map(section => ({
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
  };

  const frontendSections = convertToFrontendSections(dashboard.sections);

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between px-6 py-6 border-b bg-background">
        <div>
          <h2 className="comet-title-l">{dashboard.name}</h2>
          <p className="text-sm text-gray-600 mt-1">
            {dashboard.description || "Organize your panels into collapsible sections"}
          </p>
        </div>
        <button
          onClick={handleAddSection}
          className="btn btn-primary flex items-center gap-2 hover:bg-blue-700 transition-colors"
          disabled={createSectionMutation.isPending}
        >
          <Plus size={16} />
          {createSectionMutation.isPending ? "Adding..." : "Add Section"}
        </button>
      </div>

      <div className="px-6">
        {frontendSections.map((section: PanelSection) => (
          <div key={section.id} className="border rounded-lg bg-white shadow-sm hover:shadow-md transition-shadow mb-6">
            <div className="flex items-center justify-between p-4 border-b bg-white rounded-t-lg">
              <div className="flex items-center gap-2">
                <button
                  onClick={() => toggleSectionExpanded(section.id)}
                  className="p-1 hover:bg-gray-200 rounded transition-colors"
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
                  <button
                    onClick={() => handleAddPanel(section.id)}
                    className="btn btn-sm btn-primary flex items-center gap-1 hover:bg-blue-700 transition-colors"
                    disabled={createPanelMutation.isPending}
                  >
                    <Plus size={14} />
                    {createPanelMutation.isPending && modalSectionId === section.id ? "Adding..." : "Add Panel"}
                  </button>
                )}
              </div>
            </div>

            {section.isExpanded && (
              <div className="p-4">
                {section.items.length === 0 ? (
                  <div className="text-center py-8 text-gray-500">
                    <div className="text-4xl mb-2">📊</div>
                    <p>No panels in this section yet.</p>
                    <button
                      onClick={() => handleAddPanel(section.id)}
                      className="btn btn-primary mt-4"
                      disabled={createPanelMutation.isPending}
                    >
                      {createPanelMutation.isPending && modalSectionId === section.id ? "Adding..." : "Add First Panel"}
                    </button>
                  </div>
                ) : (
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
                )}
              </div>
            )}
          </div>
        ))}
      </div>

      <PanelModal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        onSave={handleSavePanel}
        panel={editingPanel}
        sectionId={modalSectionId}
      />
    </div>
  );
};

export default DashboardSections;