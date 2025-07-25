import React, { useState, useEffect } from 'react';
import { Responsive, WidthProvider } from 'react-grid-layout';
import { RefreshCw, Maximize2, Minimize2, Download } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useWidgetData, useAutoRefresh, useWidgetDataManager } from '@/hooks/useWidgetData';
import { Dashboard, DashboardLayout } from '@/types/dashboard';
import { convertToReactGridLayout } from '@/utils/gridHelpers';
import LineChart from '@/components/widgets/LineChart';
import BarChart from '@/components/widgets/BarChart';
import PieChart from '@/components/widgets/PieChart';
import DataTable from '@/components/widgets/DataTable';
import KPICard from '@/components/widgets/KPICard';
import Heatmap from '@/components/widgets/Heatmap';
import AreaChart from '@/components/widgets/AreaChart';
import DonutChart from '@/components/widgets/DonutChart';
import ScatterPlot from '@/components/widgets/ScatterPlot';
import GaugeChart from '@/components/widgets/GaugeChart';
import ProgressBar from '@/components/widgets/ProgressBar';
import NumberCard from '@/components/widgets/NumberCard';
import FunnelChart from '@/components/widgets/FunnelChart';
import HorizontalBarChart from '@/components/widgets/HorizontalBarChart';
import dataService from '@/services/dataService';

const ResponsiveGridLayout = WidthProvider(Responsive);

interface DashboardRendererProps {
  dashboard: Dashboard;
  isFullscreen?: boolean;
  onToggleFullscreen?: () => void;
  globalFilters?: Record<string, any>;
  onGlobalFiltersChange?: (filters: Record<string, any>) => void;
}

const DashboardRenderer: React.FC<DashboardRendererProps> = ({
  dashboard,
  isFullscreen = false,
  onToggleFullscreen,
  globalFilters = {},
  onGlobalFiltersChange,
}) => {
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [refreshInterval, setRefreshInterval] = useState(dashboard.refreshInterval || 30);
  const { refreshAllWidgets } = useWidgetDataManager();

  // Auto-refresh functionality
  useAutoRefresh(autoRefresh, refreshInterval, () => {
    refreshAllWidgets();
  });

  const handleManualRefresh = () => {
    refreshAllWidgets();
  };

  const handleExport = () => {
    // TODO: Implement dashboard export functionality
    console.log('Exporting dashboard...');
  };

  const renderWidget = (widget: DashboardLayout) => {
    
    const commonProps = {
      id: widget.id,
      title: widget.config.title,
      onRefresh: () => refreshAllWidgets(),
    };

    switch (widget.type) {
      case 'line_chart':
        return <LineChartWidget key={widget.id} widget={widget} globalFilters={globalFilters} {...commonProps} />;
      case 'bar_chart':
        return <BarChartWidget key={widget.id} widget={widget} globalFilters={globalFilters} {...commonProps} />;
      case 'pie_chart':
        return <PieChartWidget key={widget.id} widget={widget} globalFilters={globalFilters} {...commonProps} />;
      case 'table':
        return <TableWidget key={widget.id} widget={widget} globalFilters={globalFilters} {...commonProps} />;
      case 'kpi_card':
        return <KPICardWidget key={widget.id} widget={widget} globalFilters={globalFilters} {...commonProps} />;
      case 'heatmap':
        return <HeatmapWidget key={widget.id} widget={widget} globalFilters={globalFilters} {...commonProps} />;
      case 'area_chart':
        return <AreaChartWidget key={widget.id} widget={widget} globalFilters={globalFilters} {...commonProps} />;
      case 'donut_chart':
        return <DonutChartWidget key={widget.id} widget={widget} globalFilters={globalFilters} {...commonProps} />;
      case 'scatter_plot':
        return <ScatterPlotWidget key={widget.id} widget={widget} globalFilters={globalFilters} {...commonProps} />;
      case 'gauge_chart':
        return <GaugeChartWidget key={widget.id} widget={widget} globalFilters={globalFilters} {...commonProps} />;
      case 'progress_bar':
        return <ProgressBarWidget key={widget.id} widget={widget} globalFilters={globalFilters} {...commonProps} />;
      case 'number_card':
        return <NumberCardWidget key={widget.id} widget={widget} globalFilters={globalFilters} {...commonProps} />;
      case 'funnel_chart':
        return <FunnelChartWidget key={widget.id} widget={widget} globalFilters={globalFilters} {...commonProps} />;
      case 'horizontal_bar_chart':
        return <HorizontalBarChartWidget key={widget.id} widget={widget} globalFilters={globalFilters} {...commonProps} />;
      default:
        return <div key={widget.id}>Unknown widget type: {widget.type}</div>;
    }
  };

  const reactGridLayout = convertToReactGridLayout(dashboard.layout.grid);

  return (
    <div className={`${isFullscreen ? 'fixed inset-0 z-50 bg-background' : 'h-screen'}`}>
      {/* Top Controls */}
      {!isFullscreen && (
        <div className="border-b border-border p-4">
          <div className="flex items-center justify-between">
            <div>
              <h1 className="text-2xl font-bold">{dashboard.name}</h1>
              {dashboard.description && (
                <p className="text-muted-foreground mt-1">{dashboard.description}</p>
              )}
            </div>
            
            <div className="flex items-center space-x-4">
              <div className="flex items-center space-x-2">
                <label className="text-sm text-muted-foreground">Auto-refresh:</label>
                <input
                  type="checkbox"
                  checked={autoRefresh}
                  onChange={(e) => setAutoRefresh(e.target.checked)}
                  className="rounded"
                />
              </div>
              
              <Select value={refreshInterval.toString()} onValueChange={(value) => setRefreshInterval(Number(value))}>
                <SelectTrigger className="w-32">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="10">10s</SelectItem>
                  <SelectItem value="30">30s</SelectItem>
                  <SelectItem value="60">1m</SelectItem>
                  <SelectItem value="300">5m</SelectItem>
                  <SelectItem value="600">10m</SelectItem>
                </SelectContent>
              </Select>
              
              <Button variant="outline" size="sm" onClick={handleManualRefresh}>
                <RefreshCw className="h-4 w-4 mr-2" />
                Refresh
              </Button>
              
              <Button variant="outline" size="sm" onClick={onToggleFullscreen}>
                {isFullscreen ? (
                  <Minimize2 className="h-4 w-4 mr-2" />
                ) : (
                  <Maximize2 className="h-4 w-4 mr-2" />
                )}
                {isFullscreen ? 'Exit Fullscreen' : 'Fullscreen'}
              </Button>
              
              <Button variant="outline" size="sm" onClick={handleExport}>
                <Download className="h-4 w-4 mr-2" />
                Export
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* Dashboard Grid */}
      <div className={`${isFullscreen ? 'p-4' : 'p-4 h-[calc(100vh-120px)]'} overflow-auto`}>
        {dashboard.layout.grid.length === 0 ? (
          <div className="flex items-center justify-center h-full">
            <div className="text-center">
              <h3 className="text-lg font-semibold mb-2">No widgets yet</h3>
              <p className="text-muted-foreground">
                Add widgets to start building your dashboard
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
            isDraggable={false}
            isResizable={false}
            margin={[16, 16]}
            containerPadding={[0, 0]}
          >
            {dashboard.layout.grid.map((widget) => (
              <div key={widget.id} className="bg-background">
                {renderWidget(widget)}
              </div>
            ))}
          </ResponsiveGridLayout>
        )}
      </div>
    </div>
  );
};

interface WidgetWrapperProps {
  widget: DashboardLayout;
  globalFilters: Record<string, any>;
  [key: string]: any;
}

const LineChartWidget: React.FC<WidgetWrapperProps> = ({ widget, globalFilters, ...props }) => {
  const { data: rawData, loading, error } = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource || '',
      method: 'GET',
      queryParams: widget.config.queryParams,
    },
    globalFilters,
    { enabled: !!widget.config.dataSource }
  );
  
  // Transform raw API data into chart format
  const chartData = React.useMemo(() => {
    if (!rawData || !widget.config.chartOptions) return [];
    return dataService.transformDataForChart(rawData, widget.config.chartOptions, 'line_chart');
  }, [rawData, widget.config.chartOptions]);
  
  return <LineChart id={widget.id} title={widget.config.title} data={chartData as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

const BarChartWidget: React.FC<WidgetWrapperProps> = ({ widget, globalFilters, ...props }) => {
  const { data: rawData, loading, error } = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource || '',
      method: 'GET',
      queryParams: widget.config.queryParams,
    },
    globalFilters,
    { enabled: !!widget.config.dataSource }
  );
  
  // Transform raw API data into chart format
  const chartData = React.useMemo(() => {
    if (!rawData || !widget.config.chartOptions) return [];
    return dataService.transformDataForChart(rawData, widget.config.chartOptions, 'bar_chart');
  }, [rawData, widget.config.chartOptions]);
  
  return <BarChart id={widget.id} title={widget.config.title} data={chartData as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

const PieChartWidget: React.FC<WidgetWrapperProps> = ({ widget, globalFilters, ...props }) => {
  const { data: rawData, loading, error } = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource || '',
      method: 'GET',
      queryParams: widget.config.queryParams,
    },
    globalFilters,
    { enabled: !!widget.config.dataSource }
  );
  
  // Transform raw API data into chart format
  const chartData = React.useMemo(() => {
    if (!rawData || !widget.config.chartOptions) return [];
    return dataService.transformDataForChart(rawData, widget.config.chartOptions, 'pie_chart');
  }, [rawData, widget.config.chartOptions]);
  
  return <PieChart id={widget.id} title={widget.config.title} data={chartData as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

const TableWidget: React.FC<WidgetWrapperProps> = ({ widget, globalFilters, ...props }) => {
  const { data: rawData, loading, error, pagination } = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource || '',
      method: 'GET',
      queryParams: widget.config.queryParams,
    },
    globalFilters,
    { enabled: !!widget.config.dataSource }
  );
  
  // Transform raw API data into table format
  const tableData = React.useMemo(() => {
    if (!rawData) return [];
    return dataService.transformDataForChart(rawData, widget.config.chartOptions, 'table');
  }, [rawData, widget.config.chartOptions]);
  
  return <DataTable id={widget.id} title={widget.config.title} data={tableData as any} pagination={pagination} loading={loading} error={error} {...props} />;
};

const KPICardWidget: React.FC<WidgetWrapperProps> = ({ widget, globalFilters, ...props }) => {
  const { data: rawData, loading, error } = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource || '',
      method: 'GET',
      queryParams: widget.config.queryParams,
    },
    globalFilters,
    { enabled: !!widget.config.dataSource }
  );
  
  // For KPI cards, extract a single value based on configuration
  const kpiData = React.useMemo(() => {
    if (!rawData || !widget.config.chartOptions?.yAxisKey) return null;
    
    const values = dataService.extractValueByPath(rawData, widget.config.chartOptions.yAxisKey);
    const value = values.length > 0 ? values[0] : 0;
    
    return {
      value: Number(value) || 0,
      label: widget.config.chartOptions.yAxisKey.split('.').pop() || 'Value',
      format: 'number' as const
    };
  }, [rawData, widget.config.chartOptions]);
  
  return <KPICard id={widget.id} title={widget.config.title} data={kpiData as any} loading={loading} error={error} {...props} />;
};

const HeatmapWidget: React.FC<WidgetWrapperProps> = ({ widget, globalFilters, ...props }) => {
  const { data: rawData, loading, error } = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource || '',
      method: 'GET',
      queryParams: widget.config.queryParams,
    },
    globalFilters,
    { enabled: !!widget.config.dataSource }
  );
  
  // Transform raw API data into heatmap format
  const heatmapData = React.useMemo(() => {
    if (!rawData || !widget.config.chartOptions) return [];
    
    const transformedData = dataService.transformDataForChart(rawData, widget.config.chartOptions, 'heatmap');
    // Convert to heatmap format if needed
    return transformedData.map((item: any, index: number) => ({
      x: String(item.x || index),
      y: String(item.y || 'series'),
      value: Number(item.y) || 0
    }));
  }, [rawData, widget.config.chartOptions]);
  
  return <Heatmap id={widget.id} title={widget.config.title} data={heatmapData as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

const AreaChartWidget: React.FC<WidgetWrapperProps> = ({ widget, globalFilters, ...props }) => {
  const { data: rawData, loading, error } = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource || '',
      method: 'GET',
      queryParams: widget.config.queryParams,
    },
    globalFilters,
    { enabled: !!widget.config.dataSource }
  );
  
  // Transform raw API data into chart format
  const chartData = React.useMemo(() => {
    if (!rawData || !widget.config.chartOptions) return [];
    return dataService.transformDataForChart(rawData, widget.config.chartOptions, 'line_chart');
  }, [rawData, widget.config.chartOptions]);
  
  return <AreaChart id={widget.id} title={widget.config.title} data={chartData as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

const DonutChartWidget: React.FC<WidgetWrapperProps> = ({ widget, globalFilters, ...props }) => {
  const { data: rawData, loading, error } = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource || '',
      method: 'GET',
      queryParams: widget.config.queryParams,
    },
    globalFilters,
    { enabled: !!widget.config.dataSource }
  );
  
  // Transform raw API data into chart format
  const chartData = React.useMemo(() => {
    if (!rawData || !widget.config.chartOptions) return [];
    return dataService.transformDataForChart(rawData, widget.config.chartOptions, 'pie_chart');
  }, [rawData, widget.config.chartOptions]);
  
  return <DonutChart id={widget.id} title={widget.config.title} data={chartData as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

const ScatterPlotWidget: React.FC<WidgetWrapperProps> = ({ widget, globalFilters, ...props }) => {
  const { data: rawData, loading, error } = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource || '',
      method: 'GET',
      queryParams: widget.config.queryParams,
    },
    globalFilters,
    { enabled: !!widget.config.dataSource }
  );
  
  // Transform raw API data into scatter plot format
  const scatterData = React.useMemo(() => {
    if (!rawData || !widget.config.chartOptions) return [];
    
    const transformedData = dataService.transformDataForChart(rawData, widget.config.chartOptions, 'scatter_plot');
    return transformedData.map((item: any, index: number) => ({
      x: Number(item.x) || index,
      y: Number(item.y) || 0,
      label: item.label || `Point ${index + 1}`
    }));
  }, [rawData, widget.config.chartOptions]);
  
  return <ScatterPlot id={widget.id} title={widget.config.title} data={scatterData as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

const GaugeChartWidget: React.FC<WidgetWrapperProps> = ({ widget, globalFilters, ...props }) => {
  const { data: rawData, loading, error } = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource || '',
      method: 'GET',
      queryParams: widget.config.queryParams,
    },
    globalFilters,
    { enabled: !!widget.config.dataSource }
  );
  
  // Transform raw API data into gauge format
  const gaugeData = React.useMemo(() => {
    if (!rawData || !widget.config.chartOptions?.yAxisKey) return null;
    
    const values = dataService.extractValueByPath(rawData, widget.config.chartOptions.yAxisKey);
    const value = values.length > 0 ? values[0] : 0;
    
    return {
      value: Number(value) || 0,
      min: widget.config.chartOptions.minValue || 0,
      max: widget.config.chartOptions.maxValue || 100,
      label: widget.config.chartOptions.yAxisKey.split('.').pop() || 'Value',
      thresholds: widget.config.chartOptions.thresholds
    };
  }, [rawData, widget.config.chartOptions]);
  
  return <GaugeChart id={widget.id} title={widget.config.title} data={gaugeData as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

const ProgressBarWidget: React.FC<WidgetWrapperProps> = ({ widget, globalFilters, ...props }) => {
  const { data: rawData, loading, error } = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource || '',
      method: 'GET',
      queryParams: widget.config.queryParams,
    },
    globalFilters,
    { enabled: !!widget.config.dataSource }
  );
  
  // Transform raw API data into progress format
  const progressData = React.useMemo(() => {
    if (!rawData || !widget.config.chartOptions?.yAxisKey) return null;
    
    const values = dataService.extractValueByPath(rawData, widget.config.chartOptions.yAxisKey);
    const value = values.length > 0 ? values[0] : 0;
    const target = widget.config.chartOptions.targetValue || 100;
    
    return {
      value: Number(value) || 0,
      target: Number(target) || 100,
      label: widget.config.chartOptions.yAxisKey.split('.').pop() || 'Progress',
      unit: widget.config.chartOptions.unit || ''
    };
  }, [rawData, widget.config.chartOptions]);
  
  return <ProgressBar id={widget.id} title={widget.config.title} data={progressData as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

const NumberCardWidget: React.FC<WidgetWrapperProps> = ({ widget, globalFilters, ...props }) => {
  const { data: rawData, loading, error } = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource || '',
      method: 'GET',
      queryParams: widget.config.queryParams,
    },
    globalFilters,
    { enabled: !!widget.config.dataSource }
  );
  
  // Transform raw API data into number card format
  const numberData = React.useMemo(() => {
    if (!rawData || !widget.config.chartOptions?.yAxisKey) return null;
    
    const values = dataService.extractValueByPath(rawData, widget.config.chartOptions.yAxisKey);
    const value = values.length > 0 ? values[0] : 0;
    
    return {
      value: Number(value) || 0,
      label: widget.config.chartOptions.yAxisKey.split('.').pop() || 'Value',
      unit: widget.config.chartOptions.unit || '',
      format: widget.config.chartOptions.format || 'number'
    };
  }, [rawData, widget.config.chartOptions]);
  
  return <NumberCard id={widget.id} title={widget.config.title} data={numberData as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

const FunnelChartWidget: React.FC<WidgetWrapperProps> = ({ widget, globalFilters, ...props }) => {
  const { data: rawData, loading, error } = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource || '',
      method: 'GET',
      queryParams: widget.config.queryParams,
    },
    globalFilters,
    { enabled: !!widget.config.dataSource }
  );
  
  // Transform raw API data into funnel format
  const funnelData = React.useMemo(() => {
    if (!rawData || !widget.config.chartOptions) return [];
    
    const transformedData = dataService.transformDataForChart(rawData, widget.config.chartOptions, 'funnel_chart');
    return transformedData.map((item: any) => ({
      name: item.name || item.category || 'Step',
      value: Number(item.value) || Number(item.count) || 0
    }));
  }, [rawData, widget.config.chartOptions]);
  
  return <FunnelChart id={widget.id} title={widget.config.title} data={funnelData as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

const HorizontalBarChartWidget: React.FC<WidgetWrapperProps> = ({ widget, globalFilters, ...props }) => {
  const { data: rawData, loading, error } = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource || '',
      method: 'GET',
      queryParams: widget.config.queryParams,
    },
    globalFilters,
    { enabled: !!widget.config.dataSource }
  );
  
  // Transform raw API data into chart format
  const chartData = React.useMemo(() => {
    if (!rawData || !widget.config.chartOptions) return [];
    return dataService.transformDataForChart(rawData, widget.config.chartOptions, 'bar_chart');
  }, [rawData, widget.config.chartOptions]);
  
  return <HorizontalBarChart id={widget.id} title={widget.config.title} data={chartData as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

export default DashboardRenderer;
