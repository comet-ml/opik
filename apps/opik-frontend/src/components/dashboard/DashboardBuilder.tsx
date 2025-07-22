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
import { useWidgetData } from '@/hooks/useWidgetData';
import dataService from '@/services/dataService';

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

  // Update local state when dashboard prop changes
  React.useEffect(() => {
    if (dashboard) {
      setDashboardName(dashboard.name || 'New Dashboard');
      setDashboardDescription(dashboard.description || '');
    }
  }, [dashboard?.id, dashboard?.name, dashboard?.description]);

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
    initialLayout: dashboard?.layout?.grid || [],
    onLayoutChange: (layout) => {
      // Only auto-save layout changes for existing widgets (position/size changes)
      // Don't auto-save when widgets are being added/removed as they may have invalid config
      if (layout.length === dashboardLayout.length) {
        const updatedDashboard = {
          ...dashboard,
          name: dashboardName,
          description: dashboardDescription,
          layout: { grid: layout },
          lastUpdatedAt: new Date().toISOString(),
          modified: new Date().toISOString(),
        };
        onSave(updatedDashboard);
      }
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
    toast({
      title: 'Widget deleted',
      description: 'Widget has been removed from your dashboard.',
    });
  }, [removeWidget, toast]);

  const handleDuplicateWidget = useCallback((widgetId: string) => {
    duplicateWidget(widgetId);
    toast({
      title: 'Widget duplicated',
      description: 'Widget has been duplicated successfully.',
    });
  }, [duplicateWidget, toast]);

  const handleUpdateWidget = useCallback((updates: Partial<DashboardLayout>) => {
    if (selectedWidget) {
      updateWidget(selectedWidget, updates);
      const updatedDashboard = {
        ...dashboard,
        name: dashboardName,
        description: dashboardDescription,
        layout: { grid: dashboardLayout.map(w => w.id === selectedWidget ? { ...w, ...updates } : w) },
        lastUpdatedAt: new Date().toISOString(),
        modified: new Date().toISOString(),
      };
      onSave(updatedDashboard);
    }
  }, [selectedWidget, updateWidget, dashboard, dashboardName, dashboardDescription, dashboardLayout, onSave]);

  const handleSaveDashboard = useCallback(() => {
    const updatedDashboard = {
      ...dashboard,
      name: dashboardName,
      description: dashboardDescription,
      layout: { grid: dashboardLayout },
      lastUpdatedAt: new Date().toISOString(),
      modified: new Date().toISOString(),
    };
    onSave(updatedDashboard);
    toast({
      title: 'Dashboard saved',
      description: 'Your dashboard has been saved successfully.',
    });
  }, [dashboard, dashboardName, dashboardDescription, dashboardLayout, onSave, toast]);

  const selectedWidgetData = selectedWidget 
    ? dashboardLayout.find(w => w.id === selectedWidget) 
    : null;

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
        <div className="flex-1 relative overflow-hidden">
          <div className="absolute inset-0 p-4 overflow-auto">
            {dashboardLayout.length === 0 ? (
              <div className="flex items-center justify-center h-full">
                <div className="text-center max-w-md">
                  <h3 className="text-lg font-semibold mb-2">Start Building Your Dashboard</h3>
                  <p className="text-muted-foreground mb-4">
                    Use the widget library on the left to add your first widget to the dashboard.
                  </p>
                </div>
              </div>
            ) : (
              <ResponsiveGridLayout
                className="layout"
                layouts={{ lg: reactGridLayout }}
                breakpoints={{ lg: 1200, md: 996, sm: 768, xs: 480, xxs: 0 }}
                cols={{ lg: 12, md: 10, sm: 6, xs: 4, xxs: 2 }}
                rowHeight={60}
                isDraggable={!isDragging && !isResizing}
                isResizable={!isDragging && !isResizing}
                onLayoutChange={onLayoutChange}
                onDragStart={onDragStart}
                onDragStop={onDragStop}
                onResizeStart={onResizeStart}
                onResizeStop={onResizeStop}
                margin={[16, 16]}
                containerPadding={[0, 0]}
                useCSSTransforms={false}
              >
                {dashboardLayout.map((widget) => (
                  <div key={widget.id} className="bg-background">
                    {renderWidget(widget)}
                  </div>
                ))}
              </ResponsiveGridLayout>
            )}
          </div>
        </div>

        {/* Config Panel */}
        {showConfigPanel && selectedWidgetData && (
          <div className="absolute inset-y-0 right-0 w-96 bg-background border-l border-border shadow-lg z-10">
            <ConfigPanel
              widget={selectedWidgetData}
              onClose={() => setShowConfigPanel(false)}
              onUpdate={handleUpdateWidget}
            />
          </div>
        )}
      </div>
    </div>
  );
};

// Widget wrapper components with data fetching
const LineChartWidget: React.FC<{ widget: DashboardLayout; [key: string]: any }> = ({ widget, ...props }) => {
  // Use real data if data source is configured, otherwise use mock data
  const hasDataSource = widget.config.dataSource && widget.config.dataSource.trim() !== '';
  
  const realDataQuery = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource || '',
      method: 'GET',
      queryParams: widget.config.queryParams || {},
    },
    {},
    { enabled: !!hasDataSource }
  );
  
  const mockDataQuery = useMockWidgetData(widget.id, widget.type);
  
  // Use real data if available and configured, otherwise use mock data
  const { data: rawData, loading, error } = hasDataSource ? realDataQuery : mockDataQuery;
  
  // Transform data if using real data source
  const chartData = React.useMemo(() => {
    if (!hasDataSource || !rawData || !widget.config.chartOptions) return rawData;
    return dataService.transformDataForChart(rawData, widget.config.chartOptions, 'line_chart');
  }, [hasDataSource, rawData, widget.config.chartOptions]);
  
  return <LineChart id={widget.id} title={widget.config.title} data={chartData as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

const BarChartWidget: React.FC<{ widget: DashboardLayout; [key: string]: any }> = ({ widget, ...props }) => {
  // Use real data if data source is configured, otherwise use mock data
  const hasDataSource = widget.config.dataSource && widget.config.dataSource.trim() !== '';
  
  const realDataQuery = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource || '',
      method: 'GET',
      queryParams: widget.config.queryParams || {},
    },
    {},
    { enabled: !!hasDataSource }
  );
  
  const mockDataQuery = useMockWidgetData(widget.id, widget.type);
  
  // Use real data if available and configured, otherwise use mock data
  const { data: rawData, loading, error } = hasDataSource ? realDataQuery : mockDataQuery;
  
  // Transform data if using real data source
  const chartData = React.useMemo(() => {
    if (!hasDataSource || !rawData || !widget.config.chartOptions) return rawData;
    return dataService.transformDataForChart(rawData, widget.config.chartOptions, 'bar_chart');
  }, [hasDataSource, rawData, widget.config.chartOptions]);
  
  return <BarChart id={widget.id} title={widget.config.title} data={chartData as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

const PieChartWidget: React.FC<{ widget: DashboardLayout; [key: string]: any }> = ({ widget, ...props }) => {
  // Use real data if data source is configured, otherwise use mock data
  const hasDataSource = widget.config.dataSource && widget.config.dataSource.trim() !== '';
  
  const realDataQuery = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource || '',
      method: 'GET',
      queryParams: widget.config.queryParams || {},
    },
    {},
    { enabled: !!hasDataSource }
  );
  
  const mockDataQuery = useMockWidgetData(widget.id, widget.type);
  
  // Use real data if available and configured, otherwise use mock data
  const { data: rawData, loading, error } = hasDataSource ? realDataQuery : mockDataQuery;
  
  // Transform data if using real data source
  const chartData = React.useMemo(() => {
    if (!hasDataSource || !rawData || !widget.config.chartOptions) return rawData;
    return dataService.transformDataForChart(rawData, widget.config.chartOptions, 'pie_chart');
  }, [hasDataSource, rawData, widget.config.chartOptions]);
  
  return <PieChart id={widget.id} title={widget.config.title} data={chartData as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

const TableWidget: React.FC<{ widget: DashboardLayout; [key: string]: any }> = ({ widget, ...props }) => {
  // Use real data if data source is configured, otherwise use mock data
  const hasDataSource = widget.config.dataSource && widget.config.dataSource.trim() !== '';
  
  const realDataQuery = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource || '',
      method: 'GET',
      queryParams: widget.config.queryParams || {},
    },
    {},
    { enabled: !!hasDataSource }
  );
  
  const mockDataQuery = useMockWidgetData(widget.id, widget.type);
  
  // Use real data if available and configured, otherwise use mock data
  const { data: rawData, loading, error, pagination } = hasDataSource ? realDataQuery : mockDataQuery;
  
  // Transform data if using real data source
  const tableData = React.useMemo(() => {
    if (!hasDataSource || !rawData) return rawData;
    return dataService.transformDataForChart(rawData, widget.config.chartOptions, 'table');
  }, [hasDataSource, rawData, widget.config.chartOptions]);
  
  return <DataTable id={widget.id} title={widget.config.title} data={tableData as any} pagination={pagination} loading={loading} error={error} {...props} />;
};

const KPICardWidget: React.FC<{ widget: DashboardLayout; [key: string]: any }> = ({ widget, ...props }) => {
  // Use real data if data source is configured, otherwise use mock data
  const hasDataSource = widget.config.dataSource && widget.config.dataSource.trim() !== '';
  
  const realDataQuery = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource || '',
      method: 'GET',
      queryParams: widget.config.queryParams || {},
    },
    {},
    { enabled: !!hasDataSource }
  );
  
  const mockDataQuery = useMockWidgetData(widget.id, widget.type);
  
  // Use real data if available and configured, otherwise use mock data
  const { data: rawData, loading, error } = hasDataSource ? realDataQuery : mockDataQuery;
  
  // Transform data for KPI if using real data source
  const kpiData = React.useMemo(() => {
    if (!hasDataSource) return (rawData as any)?.data || rawData;
    if (!rawData || !widget.config.chartOptions?.yAxisKey) return null;
    
    const values = dataService.extractValueByPath(rawData, widget.config.chartOptions.yAxisKey);
    const value = values.length > 0 ? values[0] : 0;
    
    return {
      value: Number(value) || 0,
      label: widget.config.chartOptions.yAxisKey.split('.').pop() || 'Value',
      format: 'number' as const
    };
  }, [hasDataSource, rawData, widget.config.chartOptions]);
  
  return <KPICard id={widget.id} title={widget.config.title} data={kpiData as any} loading={loading} error={error} {...props} />;
};

const HeatmapWidget: React.FC<{ widget: DashboardLayout; [key: string]: any }> = ({ widget, ...props }) => {
  // Use real data if data source is configured, otherwise use mock data
  const hasDataSource = widget.config.dataSource && widget.config.dataSource.trim() !== '';
  
  const realDataQuery = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource || '',
      method: 'GET',
      queryParams: widget.config.queryParams || {},
    },
    {},
    { enabled: !!hasDataSource }
  );
  
  const mockDataQuery = useMockWidgetData(widget.id, widget.type);
  
  // Use real data if available and configured, otherwise use mock data
  const { data: rawData, loading, error } = hasDataSource ? realDataQuery : mockDataQuery;
  
  // Transform data if using real data source
  const heatmapData = React.useMemo(() => {
    if (!hasDataSource || !rawData || !widget.config.chartOptions) return rawData;
    
    const transformedData = dataService.transformDataForChart(rawData, widget.config.chartOptions, 'heatmap');
    // Convert to heatmap format if needed
    return transformedData.map((item: any, index: number) => ({
      x: String(item.x || index),
      y: String(item.y || 'series'),
      value: Number(item.y) || 0
    }));
  }, [hasDataSource, rawData, widget.config.chartOptions]);
  
  return <Heatmap id={widget.id} title={widget.config.title} data={heatmapData as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

export default DashboardBuilder;
