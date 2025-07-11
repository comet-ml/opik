import React, { useState, useMemo, useCallback } from "react";
import { ChevronDown, ChevronRight, Plus, Edit2, Trash2, MoreHorizontal } from "lucide-react";
import { Panel, PanelSection } from "../CompareExperimentsPage/dashboardTypes";
import PanelGrid from "../CompareExperimentsPage/PanelGrid";
import PanelModal from "../CompareExperimentsPage/PanelModal";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

// Template-specific types for building dashboard templates
export interface TemplatePanelSection {
  id: string;
  title: string;
  position_order: number;
  panels: TemplatePanel[];
}

export interface TemplatePanel {
  id: string;
  name: string;
  type: 'PYTHON' | 'CHART' | 'TEXT' | 'METRIC' | 'HTML';
  configuration: any;
  layout: {
    x: number;
    y: number;
    w: number;
    h: number;
  };
  template_id?: string;
}

export interface DashboardTemplateConfiguration {
  sections: TemplatePanelSection[];
}

interface DashboardTemplateBuilderProps {
  initialConfiguration?: DashboardTemplateConfiguration;
  onConfigurationChange: (configuration: DashboardTemplateConfiguration) => void;
  readonly?: boolean;
}

const DashboardTemplateBuilder: React.FC<DashboardTemplateBuilderProps> = ({
  initialConfiguration,
  onConfigurationChange,
  readonly = false,
}) => {
  // Helper function to generate unique IDs
  const generateId = () => `temp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;

  // Helper function to ensure template always has a default section
  const ensureDefaultSection = (config: DashboardTemplateConfiguration): DashboardTemplateConfiguration => {
    if (config.sections.length === 0 || !config.sections.some(s => s.position_order === 0)) {
      const defaultSection: TemplatePanelSection = {
        id: generateId(),
        title: "Default Section",
        position_order: 0,
        panels: [],
      };
      
      // Shift existing sections to make room for default section
      const existingSections = config.sections.map(section => ({
        ...section,
        position_order: section.position_order + 1,
      }));
      
      return {
        sections: [defaultSection, ...existingSections],
      };
    }
    return config;
  };

  const [configuration, setConfiguration] = useState<DashboardTemplateConfiguration>(() => {
    const initialConfig = initialConfiguration || { sections: [] };
    return ensureDefaultSection(initialConfig);
  });
  const [modalOpen, setModalOpen] = useState(false);
  const [modalSectionId, setModalSectionId] = useState<string>("");
  const [editingPanel, setEditingPanel] = useState<Panel | undefined>();
  const [expandedSections, setExpandedSections] = useState<Set<string>>(
    () => new Set(configuration.sections.map(s => s.id))
  );

  // Calculate next position for panels
  const calculateNextPosition = useCallback((layout: any[]) => {
    if (layout.length === 0) {
      return { x: 0, y: 0, w: 6, h: 4 };
    }
    
    // Find the rightmost panel in the last row
    const maxY = Math.max(...layout.map(item => item.y));
    const lastRowPanels = layout.filter(item => item.y === maxY);
    const maxX = Math.max(...lastRowPanels.map(item => item.x + item.w));
    
    // If there's space in the current row, place it there
    if (maxX + 6 <= 12) {
      return { x: maxX, y: maxY, w: 6, h: 4 };
    }
    
    // Otherwise, start a new row
    return { x: 0, y: maxY + 4, w: 6, h: 4 };
  }, []);

  // Update configuration and notify parent
  const updateConfiguration = useCallback((newConfig: DashboardTemplateConfiguration) => {
    setConfiguration(newConfig);
    onConfigurationChange(newConfig);
  }, [onConfigurationChange]);

  // Section management
  const handleAddSection = useCallback(() => {
    if (readonly) return;
    
    // Count sections excluding the default section for proper numbering
    const nonDefaultSections = configuration.sections.filter(s => s.title !== "Default Section");
    
    const newSection: TemplatePanelSection = {
      id: generateId(),
      title: `Section ${nonDefaultSections.length + 1}`,
      position_order: configuration.sections.length,
      panels: [],
    };
    
    const newConfig = {
      ...configuration,
      sections: [...configuration.sections, newSection],
    };
    updateConfiguration(newConfig);
  }, [configuration, readonly, updateConfiguration]);

  const handleEditSection = useCallback((sectionId: string, newTitle: string) => {
    if (readonly) return;
    
    const newConfig = {
      ...configuration,
      sections: configuration.sections.map(section =>
        section.id === sectionId ? { ...section, title: newTitle } : section
      ),
    };
    updateConfiguration(newConfig);
  }, [configuration, readonly, updateConfiguration]);

  const handleRemoveSection = useCallback((sectionId: string) => {
    if (readonly) return;
    
    // Prevent deletion of the default section
    const sectionToRemove = configuration.sections.find(s => s.id === sectionId);
    if (sectionToRemove?.title === "Default Section") {
      return;
    }
    
    const newConfig = {
      ...configuration,
      sections: configuration.sections.filter(section => section.id !== sectionId),
    };
    updateConfiguration(newConfig);
  }, [configuration, readonly, updateConfiguration]);

  // Panel management
  const handleAddPanel = useCallback((sectionId: string) => {
    if (readonly) return;
    
    setModalSectionId(sectionId);
    setEditingPanel(undefined);
    setModalOpen(true);
  }, [readonly]);

  const handleEditPanel = useCallback((sectionId: string, panel: Panel) => {
    if (readonly) return;
    
    setModalSectionId(sectionId);
    setEditingPanel(panel);
    setModalOpen(true);
  }, [readonly]);

  const handleCloseModal = useCallback(() => {
    setModalOpen(false);
    setEditingPanel(undefined);
  }, []);

  const handleSavePanel = useCallback((panel: Panel, templateId?: string) => {
    if (readonly) return;
    
    const section = configuration.sections.find(s => s.id === modalSectionId);
    if (!section) return;

    const nextPosition = calculateNextPosition(section.panels.map(p => p.layout));
    
    const newPanel: TemplatePanel = {
      id: editingPanel?.id || generateId(),
      name: panel.name,
      type: panel.data.type.toUpperCase() as any,
      configuration: panel.data.config,
      layout: nextPosition,
      template_id: templateId,
    };

    const newConfig = {
      ...configuration,
      sections: configuration.sections.map(s =>
        s.id === modalSectionId
          ? {
              ...s,
              panels: editingPanel
                ? s.panels.map(p => p.id === editingPanel.id ? newPanel : p)
                : [...s.panels, newPanel],
            }
          : s
      ),
    };
    
    updateConfiguration(newConfig);
    handleCloseModal();
  }, [configuration, modalSectionId, editingPanel, calculateNextPosition, readonly, updateConfiguration, handleCloseModal]);

  const handleRemovePanel = useCallback((sectionId: string, panelId: string) => {
    if (readonly) return;
    
    const newConfig = {
      ...configuration,
      sections: configuration.sections.map(s =>
        s.id === sectionId
          ? { ...s, panels: s.panels.filter(p => p.id !== panelId) }
          : s
      ),
    };
    updateConfiguration(newConfig);
  }, [configuration, readonly, updateConfiguration]);

  const handleLayoutChange = useCallback((sectionId: string, layout: any[]) => {
    if (readonly) return;
    
    const newConfig = {
      ...configuration,
      sections: configuration.sections.map(s =>
        s.id === sectionId
          ? {
              ...s,
              panels: s.panels.map(panel => {
                const layoutItem = layout.find(item => item.i === panel.id);
                return layoutItem
                  ? { ...panel, layout: { x: layoutItem.x, y: layoutItem.y, w: layoutItem.w, h: layoutItem.h } }
                  : panel;
              }),
            }
          : s
      ),
    };
    updateConfiguration(newConfig);
  }, [configuration, readonly, updateConfiguration]);

  // Convert template sections to frontend format for display
  const frontendSections = useMemo((): PanelSection[] => {
    return configuration.sections.map(section => ({
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
  }, [configuration.sections, expandedSections]);

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

  // Section header renderer
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
          <h3 className="comet-title-m">
            {section.title}
            {section.title === "Default Section" && (
              <span className="ml-2 text-xs text-blue-600 bg-blue-100 px-2 py-1 rounded font-medium">
                Required
              </span>
            )}
          </h3>
          <span className="text-xs text-gray-500 bg-gray-200 px-2 py-1 rounded">
            {section.items.length} panel{section.items.length !== 1 ? 's' : ''}
          </span>
        </div>
      </div>

      <div className="flex items-center gap-2">
        {section.isExpanded && !readonly && (
          <Button
            onClick={() => handleAddPanel(section.id)}
            variant="outline"
            size="sm"
            className="flex items-center gap-1"
          >
            <Plus size={14} />
            Add Panel
          </Button>
        )}
        
        {!readonly && section.title !== "Default Section" && (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button size="sm" variant="ghost">
                <MoreHorizontal size={16} />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent>
              <DropdownMenuItem onClick={() => {
                const newTitle = prompt("Enter new section title:", section.title);
                if (newTitle && newTitle.trim()) {
                  handleEditSection(section.id, newTitle.trim());
                }
              }}>
                <Edit2 size={14} className="mr-2" />
                Edit Section
              </DropdownMenuItem>
              <DropdownMenuItem 
                onClick={() => handleRemoveSection(section.id)}
                className="text-destructive"
              >
                <Trash2 size={14} className="mr-2" />
                Remove Section
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        )}
      </div>
    </div>
  ), [toggleSectionExpanded, readonly, handleAddPanel, handleEditSection, handleRemoveSection]);

  // Empty section renderer
  const renderEmptySection = useCallback((section: PanelSection) => (
    <div className="text-center py-8 text-gray-500">
      <div className="text-4xl mb-2">📊</div>
      <p>No panels in this section yet.</p>
      {!readonly && (
        <Button
          onClick={() => handleAddPanel(section.id)}
          variant="outline"
          className="mt-4"
        >
          Add First Panel
        </Button>
      )}
    </div>
  ), [handleAddPanel, readonly]);

  // Section content renderer
  const renderSectionContent = useCallback((section: PanelSection) => {
    if (section.items.length === 0) {
      return renderEmptySection(section);
    }

    return (
      <PanelGrid
        experimentId="" // Not needed for template builder
        section={section}
        onEditPanel={(panel) => handleEditPanel(section.id, panel)}
        onRemovePanel={(panelId) => handleRemovePanel(section.id, panelId)}
        onLayoutChange={(layout) => handleLayoutChange(section.id, layout)}
      />
    );
  }, [handleEditPanel, handleRemovePanel, handleLayoutChange, renderEmptySection]);

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between px-6 py-6 border-b bg-background">
        <div>
          <h3 className="comet-title-l">Dashboard Structure</h3>
          <p className="text-sm text-gray-600 mt-1">
            Build your dashboard template by adding sections and panels
          </p>
        </div>
        {!readonly && (
          <Button
            onClick={handleAddSection}
            variant="outline"
            size="sm"
            className="flex items-center gap-2"
          >
            <Plus size={16} />
            Add Section
          </Button>
        )}
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

        {configuration.sections.length === 0 && (
          <div className="text-center py-12 mx-6">
            <div className="text-6xl mb-4">📊</div>
            <h3 className="comet-title-m mb-2">Start Building Your Dashboard</h3>
            <p className="text-muted-foreground mb-4">
              Add your first section to begin creating a reusable dashboard template
            </p>
            {!readonly && (
              <Button onClick={handleAddSection} size="lg">
                <Plus className="mr-2 size-4" />
                Add First Section
              </Button>
            )}
          </div>
        )}
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

export default DashboardTemplateBuilder; 