import React, { useCallback, useState, useMemo } from "react";
import GridLayout, { Layout } from "react-grid-layout";
import "react-grid-layout/css/styles.css";
import "react-resizable/css/styles.css";
import { Panel, PanelSectionLayout, PanelSection } from "./dashboardTypes";
import { useDashboardStore } from "./dashboardStore";
import PythonPanel from "./PythonPanel";
import ChartPanel from "./ChartPanel";
import TextPanel from "./TextPanel";
import MetricPanel from "./MetricPanel";
import HtmlPanel from "./HtmlPanel";

// TODO: adjust these constants as needed
const DASHBOARD_COLUMNS = 12;
const ROW_HEIGHT = 300;
const LAYOUT_MARGIN: [number, number] = [10, 10];

interface PanelGridProps {
  experimentId: string;
  section: PanelSection;
  onEditPanel: (panel: Panel) => void;
  onRemovePanel: (panelId: string) => void;
  onLayoutChange: (layout: PanelSectionLayout) => void;
}

const PanelGrid: React.FC<PanelGridProps> = ({ 
  experimentId, 
  section, 
  onEditPanel, 
  onRemovePanel, 
  onLayoutChange 
}) => {
  const [containerWidth, setContainerWidth] = useState<number | null>(null);
  
  const gridRef = useCallback((node: HTMLDivElement | null) => {
    if (node) {
      setContainerWidth(node.clientWidth);
    }
  }, []);

  const handleLayoutChange = useCallback(
    (newLayout: Layout[]) => {
      // Convert react-grid-layout Layout[] to PanelSectionLayout
      const converted = newLayout.map((item) => ({
        i: item.i,
        x: item.x,
        y: item.y,
        h: item.h,
        w: item.w,
      }));
      onLayoutChange(converted);
    },
    [onLayoutChange]
  );

  const handleRemovePanel = useCallback(
    (panelId: string) => {
      onRemovePanel(panelId);
    },
    [onRemovePanel]
  );

  const handleEditPanel = useCallback(
    (panel: Panel) => {
      onEditPanel(panel);
    },
    [onEditPanel]
  );

  // Memoize panel content renderer
  const renderPanelContent = useMemo(() => {
    return (panel: Panel) => {
      switch (panel.data.type) {
        case "python":
          return <PythonPanel config={panel.data.config} id={panel.id} />;
        case "chart":
          return <ChartPanel config={panel.data.config} id={panel.id} />;
        case "text":
          return <TextPanel config={panel.data.config} id={panel.id} />;
        case "metric":
          return <MetricPanel config={panel.data.config} id={panel.id} />;
        case "html":
          return <HtmlPanel config={panel.data.config} />;
        default:
          return (
            <div className="h-full flex items-center justify-center text-red-500">
              <div className="text-center">
                <div className="text-4xl mb-2">⚠️</div>
                <p>Unsupported panel type: {(panel.data as any).type}</p>
              </div>
            </div>
          );
      }
    };
  }, []);

  // Memoize panel header renderer
  const renderPanelHeader = useCallback((panel: Panel) => (
    <div className="flex items-center justify-between px-4 py-3 border-b bg-background">
      <div className="flex items-center gap-3">
        <span className="comet-body-s font-medium text-foreground">{panel.name}</span>
        <span className="comet-body-xs bg-muted text-muted-foreground px-2 py-1 rounded-sm">
          {panel.data.type}
        </span>
      </div>
      <div className="flex items-center gap-2">
        <button
          className="comet-body-xs h-8 px-3 border border-border bg-background text-foreground hover:bg-accent hover:text-accent-foreground rounded-md transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50"
          onMouseDown={(e) => {
            e.stopPropagation();
            e.preventDefault();
          }}
          onClick={(e) => {
            e.stopPropagation();
            e.preventDefault();
            handleEditPanel(panel);
          }}
        >
          Edit
        </button>
        <button
          className="comet-body-xs h-8 px-3 border border-destructive/20 bg-background text-destructive hover:bg-destructive/5 hover:border-destructive/30 rounded-md transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50"
          onMouseDown={(e) => {
            e.stopPropagation();
            e.preventDefault();
          }}
          onClick={(e) => {
            e.stopPropagation();
            e.preventDefault();
            if (confirm(`Are you sure you want to remove the panel "${panel.name}"?`)) {
              handleRemovePanel(panel.id);
            }
          }}
        >
          Remove
        </button>
      </div>
    </div>
  ), [handleEditPanel, handleRemovePanel]);

  // Memoize grid layout config
  const gridLayoutConfig = useMemo(() => ({
    cols: DASHBOARD_COLUMNS,
    compactType: "vertical" as const,
    isDraggable: true,
    isResizable: true,
    layout: section.layout,
    margin: LAYOUT_MARGIN,
    onLayoutChange: handleLayoutChange,
    preventCollision: false,
    rowHeight: ROW_HEIGHT,
    width: containerWidth || 0,
  }), [section.layout, handleLayoutChange, containerWidth]);

  if (!containerWidth) {
    return <div ref={gridRef} style={{ height: 400, width: "100%" }} />;
  }

  return (
    <div ref={gridRef} style={{ width: "100%" }}>
      <GridLayout {...gridLayoutConfig}>
        {section.items.map((panel) => (
          <div 
            key={panel.id} 
            className="bg-card rounded-md border shadow-sm overflow-hidden"
          >
            {renderPanelHeader(panel)}
            
            {/* Panel Content */}
            <div className="h-[calc(100%-65px)]">
              {renderPanelContent(panel)}
            </div>
          </div>
        ))}
      </GridLayout>
    </div>
  );
};

export default React.memo(PanelGrid); 
