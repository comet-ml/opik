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
      default:
        return <div key={widget.id}>Unknown widget type</div>;
    }
  };

  const reactGridLayout = convertToReactGridLayout(dashboard.layout.grid);

  return (
    <div className={`flex flex-col h-full bg-background ${isFullscreen ? 'fixed inset-0 z-50' : ''}`}>
      {/* Header */}
      <div className="border-b border-border p-4">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold">{dashboard.name}</h1>
            {dashboard.description && (
              <p className="text-muted-foreground mt-1">{dashboard.description}</p>
            )}
          </div>
          
          <div className="flex items-center space-x-2">
            {/* Auto-refresh controls */}
            <div className="flex items-center space-x-2">
              <span className="text-sm text-muted-foreground">Auto-refresh:</span>
              <Select
                value={autoRefresh ? refreshInterval.toString() : 'off'}
                onValueChange={(value) => {
                  if (value === 'off') {
                    setAutoRefresh(false);
                  } else {
                    setAutoRefresh(true);
                    setRefreshInterval(parseInt(value));
                  }
                }}
              >
                <SelectTrigger className="w-32">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="off">Off</SelectItem>
                  <SelectItem value="10">10s</SelectItem>
                  <SelectItem value="30">30s</SelectItem>
                  <SelectItem value="60">1m</SelectItem>
                  <SelectItem value="300">5m</SelectItem>
                </SelectContent>
              </Select>
            </div>

            <Button variant="outline" size="sm" onClick={handleManualRefresh}>
              <RefreshCw className="h-4 w-4 mr-2" />
              Refresh
            </Button>
            
            <Button variant="outline" size="sm" onClick={handleExport}>
              <Download className="h-4 w-4 mr-2" />
              Export
            </Button>

            {onToggleFullscreen && (
              <Button variant="outline" size="sm" onClick={onToggleFullscreen}>
                {isFullscreen ? (
                  <>
                    <Minimize2 className="h-4 w-4 mr-2" />
                    Exit Fullscreen
                  </>
                ) : (
                  <>
                    <Maximize2 className="h-4 w-4 mr-2" />
                    Fullscreen
                  </>
                )}
              </Button>
            )}
          </div>
        </div>
      </div>

      {/* Dashboard Grid */}
      <div className="flex-1 p-4 overflow-auto">
        {dashboard.layout.grid.length === 0 ? (
          <div className="flex items-center justify-center h-full">
            <Card className="w-96">
              <CardHeader>
                <CardTitle>Empty Dashboard</CardTitle>
              </CardHeader>
              <CardContent>
                <p className="text-muted-foreground">
                  This dashboard doesn't have any widgets yet.
                </p>
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
            isDraggable={false}
            isResizable={false}
            margin={[16, 16]}
            containerPadding={[0, 0]}
          >
            {dashboard.layout.grid.map(renderWidget)}
          </ResponsiveGridLayout>
        )}
      </div>
    </div>
  );
};

// Widget wrapper components with real data fetching
interface WidgetWrapperProps {
  widget: DashboardLayout;
  globalFilters: Record<string, any>;
  [key: string]: any;
}

const LineChartWidget: React.FC<WidgetWrapperProps> = ({ widget, globalFilters, ...props }) => {
  const { data, loading, error } = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource,
      method: 'GET',
      queryParams: widget.config.queryParams,
    },
    globalFilters,
    { enabled: !!widget.config.dataSource }
  );
  
  return <LineChart id={widget.id} title={widget.config.title} data={data as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

const BarChartWidget: React.FC<WidgetWrapperProps> = ({ widget, globalFilters, ...props }) => {
  const { data, loading, error } = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource,
      method: 'GET',
      queryParams: widget.config.queryParams,
    },
    globalFilters,
    { enabled: !!widget.config.dataSource }
  );
  
  return <BarChart id={widget.id} title={widget.config.title} data={data as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

const PieChartWidget: React.FC<WidgetWrapperProps> = ({ widget, globalFilters, ...props }) => {
  const { data, loading, error } = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource,
      method: 'GET',
      queryParams: widget.config.queryParams,
    },
    globalFilters,
    { enabled: !!widget.config.dataSource }
  );
  
  return <PieChart id={widget.id} title={widget.config.title} data={data as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

const TableWidget: React.FC<WidgetWrapperProps> = ({ widget, globalFilters, ...props }) => {
  const { data, loading, error, pagination } = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource,
      method: 'GET',
      queryParams: widget.config.queryParams,
    },
    globalFilters,
    { enabled: !!widget.config.dataSource }
  );
  
  return <DataTable id={widget.id} title={widget.config.title} data={data as any} pagination={pagination} loading={loading} error={error} {...props} />;
};

const KPICardWidget: React.FC<WidgetWrapperProps> = ({ widget, globalFilters, ...props }) => {
  const { data, loading, error } = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource,
      method: 'GET',
      queryParams: widget.config.queryParams,
    },
    globalFilters,
    { enabled: !!widget.config.dataSource }
  );
  
  return <KPICard id={widget.id} title={widget.config.title} data={(data as any)?.data || data} loading={loading} error={error} {...props} />;
};

const HeatmapWidget: React.FC<WidgetWrapperProps> = ({ widget, globalFilters, ...props }) => {
  const { data, loading, error } = useWidgetData(
    widget.id,
    {
      endpoint: widget.config.dataSource,
      method: 'GET',
      queryParams: widget.config.queryParams,
    },
    globalFilters,
    { enabled: !!widget.config.dataSource }
  );
  
  return <Heatmap id={widget.id} title={widget.config.title} data={data as any} config={widget.config.chartOptions} loading={loading} error={error} {...props} />;
};

export default DashboardRenderer;
