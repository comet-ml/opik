import React, { useState, useCallback } from 'react';
import { Responsive, WidthProvider, Layout } from 'react-grid-layout';
import { Save, Eye, Settings, Download, Upload } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useToast } from '@/components/ui/use-toast';
import WidgetLibrary from './WidgetLibrary';
import ConfigPanel from './ConfigPanel';
import { useGridLayout } from '@/hooks/useGridLayout';
import { useMockWidgetData } from '@/hooks/useWidgetData';
import { Dashboard, WidgetType, DashboardLayout } from '@/types/dashboard';
import LineChart from '@/components/widgets/LineChart';
import BarChart from '@/components/widgets/BarChart';
import PieChart from '@/components/widgets/PieChart';
import DataTable from '@/components/widgets/DataTable';
import KPICard from '@/components/widgets/KPICard';
import Heatmap from '@/components/widgets/Heatmap';

const ResponsiveGridLayout = WidthProvider(Responsive);

interface DashboardBuilderProps {
  dashboard?: Dashboard;
  onSave: (dashboard: Partial<Dashboard>) => void;
  onPreview: () => void;
}

const DashboardBuilder: React.FC<DashboardBuilderProps> = ({
  dashboard,
  onSave,
  onPreview,
}) => {
  const { toast } = useToast();
  const [dashboardName, setDashboardName] = useState(dashboard?.name || 'New Dashboard');
  const [dashboardDescription, setDashboardDescription] = useState(dashboard?.description || '');
  const [selectedWidget, setSelectedWidget] = useState<string | null>(null);
  const [showConfigPanel, setShowConfigPanel] = useState(false);

  const {
    dashboardLayout,
    reactGridLayout,
    isDragging,
    isResizing,
    addWidget,
    removeWidget,
    updateWidget,
    duplicateWidget,
    onLayoutChange,
    onDragStart,
    onDragStop,
    onResizeStart,
    onResizeStop,
  } = useGridLayout({
    initialLayout: dashboard?.layout.grid || [],
    onLayoutChange: (layout) => {
      // Auto-save layout changes
      const updatedDashboard = {
        ...dashboard,
        name: dashboardName,
        description: dashboardDescription,
        layout: { grid: layout },
        modified: new Date().toISOString(),
      };
      onSave(updatedDashboard);
    },
  });

  const handleAddWidget = useCallback((widgetType: WidgetType) => {
    const widgetId = addWidget(widgetType);
    setSelectedWidget(widgetId);
    setShowConfigPanel(true);
    toast({
      title: 'Widget added',
      description: `${widgetType.replace('_', ' ')} widget has been added to your dashboard.`,
    });
  }, [addWidget, toast]);

  const handleEditWidget = useCallback((widgetId: string) => {
    setSelectedWidget(widgetId);
    setShowConfigPanel(true);
  }, []);

  const handleDeleteWidget = useCallback((widgetId: string) => {
    removeWidget(widgetId);
    setSelectedWidget(null);
    setShowConfigPanel(false);
    toast({
      title: 'Widget removed',
      description: 'Widget has been removed from your dashboard.',
    });
  }, [removeWidget, toast]);

  const handleDuplicateWidget = useCallback((widgetId: string) => {
    const newWidgetId = duplicateWidget(widgetId);
    if (newWidgetId) {
      toast({
        title: 'Widget duplicated',
        description: 'Widget has been duplicated successfully.',
      });
    }
  }, [duplicateWidget, toast]);

  const handleSaveDashboard = useCallback(() => {
    const updatedDashboard = {
      ...dashboard,
      name: dashboardName,
      description: dashboardDescription,
      layout: { grid: dashboardLayout },
      modified: new Date().toISOString(),
    };
    onSave(updatedDashboard);
    toast({
      title: 'Dashboard saved',
      description: 'Your dashboard has been saved successfully.',
    });
  }, [dashboard, dashboardName, dashboardDescription, dashboardLayout, onSave, toast]);

  const renderWidget = useCallback((widget: DashboardLayout) => {
    const commonProps = {
      id: widget.id,
      title: widget.config.title,
      onEdit: () => handleEditWidget(widget.id),
      onDelete: () => handleDeleteWidget(widget.id),
      onDuplicate: () => handleDuplicateWidget(widget.id),
    };

    switch (widget.type) {
      case 'line_chart':
        return <LineChartWidget key={widget.id} widget={widget} {...commonProps} />;
      case 'bar_chart':
        return <BarChartWidget key={widget.id} widget={widget} {...commonProps} />;
      case 'pie_chart':
        return <PieChartWidget key={widget.id} widget={widget} {...commonProps} />;
      case 'table':
        return <TableWidget key={widget.id} widget={widget} {...commonProps} />;
      case 'kpi_card':
        return <KPICardWidget key={widget.id} widget={widget} {...commonProps} />;
      case 'heatmap':
        return <HeatmapWidget key={widget.id} widget={widget} {...commonProps} />;
      default:
        return <div key={widget.id}>Unknown widget type</div>;
    }
  }, [handleEditWidget, handleDeleteWidget, handleDuplicateWidget]);

  return (
    <div className="flex h-screen bg-background">
      {/* Widget Library Sidebar */}
      <WidgetLibrary onAddWidget={handleAddWidget} />

      {/* Main Dashboard Area */}
      <div className="flex-1 flex flex-col">
        {/* Top Toolbar */}
        <div className="border-b border-border p-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-4">
              <div className="flex flex-col space-y-1">
                <Input
                  value={dashboardName}
                  onChange={(e) => setDashboardName(e.target.value)}
                  className="text-lg font-semibold border-none p-0 h-auto focus-visible:ring-0"
                  placeholder="Dashboard name"
                />
                <Input
                  value={dashboardDescription}
                  onChange={(e) => setDashboardDescription(e.target.value)}
                  className="text-sm text-muted-foreground border-none p-0 h-auto focus-visible:ring-0"
                  placeholder="Add a description..."
                />
              </div>
            </div>
            
            <div className="flex items-center space-x-2">
              <Button variant="outline" onClick={onPreview}>
                <Eye className="h-4 w-4 mr-2" />
                Preview
              </Button>
              <Button onClick={handleSaveDashboard}>
                <Save className="h-4 w-4 mr-2" />
                Save
              </Button>
            </div>
          </div>
        </div>

        {/* Dashboard Grid */}
        <div className="flex-1 p-4 overflow-auto">
          {dashboardLayout.length === 0 ? (
            <div className="flex items-center justify-center h-full">
              <Card className="w-96">
                <CardHeader>
                  <CardTitle>Start Building Your Dashboard</CardTitle>
                </CardHeader>
                <CardContent>
                  <p className="text-muted-foreground mb-4">
                    Add widgets from the library on the left to get started.
                  </p>
                  <Button onClick={() => handleAddWidget('line_chart')} className="w-full">
                    Add Your First Widget
                  </Button>
                </CardContent>
              </Card>
            </div>
          ) : (
            <ResponsiveGridLayout
              className="layout"
              layouts={{ lg: reactGridLayout }}
              breakpoints={{ lg: 1200, md: 996, sm: 768, xs: 480, xxs: 0 }}
              cols={{ lg: 12, md: 10, sm: 6, xs: 4, xxs: 2 }}
              rowHeight={60}
              onLayoutChange={onLayoutChange}
              onDragStart={onDragStart}
              onDragStop={onDragStop}
              onResizeStart={onResizeStart}
              onResizeStop={onResizeStop}
              isDraggable={!showConfigPanel}
              isResizable={!showConfigPanel}
              margin={[16, 16]}
              containerPadding={[0, 0]}
            >
              {dashboardLayout.map(renderWidget)}
            </ResponsiveGridLayout>
          )}
        </div>
      </div>

      {/* Configuration Panel */}
      {showConfigPanel && selectedWidget && (
        <ConfigPanel
          widget={dashboardLayout.find(w => w.id === selectedWidget)}
          onClose={() => {
            setShowConfigPanel(false);
            setSelectedWidget(null);
          }}
          onUpdate={(updates) => {
            if (selectedWidget) {
              updateWidget(selectedWidget, updates);
            }
          }}
        />
      )}
    </div>
  );
};

// Widget wrapper components with data fetching
const LineChartWidget: React.FC<{ widget: DashboardLayout; [key: string]: any }> = ({ widget, ...props }) => {
  const { data, loading, error } = useMockWidgetData(widget.id, widget.type);
  return <LineChart id={widget.id} title={widget.config.title} data={data as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

const BarChartWidget: React.FC<{ widget: DashboardLayout; [key: string]: any }> = ({ widget, ...props }) => {
  const { data, loading, error } = useMockWidgetData(widget.id, widget.type);
  return <BarChart id={widget.id} title={widget.config.title} data={data as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

const PieChartWidget: React.FC<{ widget: DashboardLayout; [key: string]: any }> = ({ widget, ...props }) => {
  const { data, loading, error } = useMockWidgetData(widget.id, widget.type);
  return <PieChart id={widget.id} title={widget.config.title} data={data as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

const TableWidget: React.FC<{ widget: DashboardLayout; [key: string]: any }> = ({ widget, ...props }) => {
  const { data, loading, error, pagination } = useMockWidgetData(widget.id, widget.type);
  return <DataTable id={widget.id} title={widget.config.title} data={data as any} pagination={pagination} loading={loading} error={error} {...props} />;
};

const KPICardWidget: React.FC<{ widget: DashboardLayout; [key: string]: any }> = ({ widget, ...props }) => {
  const { data, loading, error } = useMockWidgetData(widget.id, widget.type);
  return <KPICard id={widget.id} title={widget.config.title} data={(data as any)?.data || data} loading={loading} error={error} {...props} />;
};

const HeatmapWidget: React.FC<{ widget: DashboardLayout; [key: string]: any }> = ({ widget, ...props }) => {
  const { data, loading, error } = useMockWidgetData(widget.id, widget.type);
  return <Heatmap id={widget.id} title={widget.config.title} data={data as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

export default DashboardBuilder;
