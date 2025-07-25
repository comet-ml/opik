import React from 'react';
import { 
  TrendingUp, 
  BarChart3, 
  PieChart as PieChartIcon, 
  Table, 
  Gauge, 
  Grid3X3,
  AreaChart,
  Target,
  Zap,
  Activity,
  Hash,
  Filter,
  BarChart2
} from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { WidgetType } from '@/types/dashboard';

interface WidgetLibraryProps {
  onAddWidget: (widgetType: WidgetType) => void;
}

interface WidgetTypeInfo {
  type: WidgetType;
  name: string;
  description: string;
  icon: React.ReactNode;
  category: string;
}

const widgetTypes: WidgetTypeInfo[] = [
  {
    type: 'line_chart',
    name: 'Line Chart',
    description: 'Time series data visualization',
    icon: <TrendingUp className="h-6 w-6" />,
    category: 'Charts',
  },
  {
    type: 'bar_chart',
    name: 'Bar Chart',
    description: 'Categorical data comparison',
    icon: <BarChart3 className="h-6 w-6" />,
    category: 'Charts',
  },
  {
    type: 'pie_chart',
    name: 'Pie Chart',
    description: 'Proportional data display',
    icon: <PieChartIcon className="h-6 w-6" />,
    category: 'Charts',
  },
  {
    type: 'table',
    name: 'Data Table',
    description: 'Tabular data display',
    icon: <Table className="h-6 w-6" />,
    category: 'Data',
  },
  {
    type: 'kpi_card',
    name: 'KPI Card',
    description: 'Key performance indicators',
    icon: <Gauge className="h-6 w-6" />,
    category: 'Metrics',
  },
  {
    type: 'heatmap',
    name: 'Heatmap',
    description: 'Correlation matrix visualization',
    icon: <Grid3X3 className="h-6 w-6" />,
    category: 'Charts',
  },
  {
    type: 'area_chart',
    name: 'Area Chart',
    description: 'Filled line charts for trend visualization',
    icon: <AreaChart className="h-6 w-6" />,
    category: 'Charts',
  },
  {
    type: 'donut_chart',
    name: 'Donut Chart',
    description: 'Pie chart with center hole',
    icon: <Target className="h-6 w-6" />,
    category: 'Charts',
  },
  {
    type: 'scatter_plot',
    name: 'Scatter Plot',
    description: 'Correlation analysis between variables',
    icon: <Zap className="h-6 w-6" />,
    category: 'Charts',
  },
  {
    type: 'gauge_chart',
    name: 'Gauge Chart',
    description: 'Speedometer-style metrics',
    icon: <Activity className="h-6 w-6" />,
    category: 'Metrics',
  },
  {
    type: 'progress_bar',
    name: 'Progress Bar',
    description: 'Linear progress indicators',
    icon: <BarChart2 className="h-6 w-6" />,
    category: 'Metrics',
  },
  {
    type: 'number_card',
    name: 'Number Card',
    description: 'Large number displays with trends',
    icon: <Hash className="h-6 w-6" />,
    category: 'Metrics',
  },
  {
    type: 'funnel_chart',
    name: 'Funnel Chart',
    description: 'Conversion analysis visualization',
    icon: <Filter className="h-6 w-6" />,
    category: 'Analytics',
  },
  {
    type: 'horizontal_bar_chart',
    name: 'Horizontal Bar Chart',
    description: 'Alternative bar chart layout',
    icon: <BarChart3 className="h-6 w-6 rotate-90" />,
    category: 'Charts',
  },
];

const WidgetLibrary: React.FC<WidgetLibraryProps> = ({ onAddWidget }) => {
  const categories = [...new Set(widgetTypes.map(w => w.category))];

  return (
    <div className="w-80 border-r border-border bg-muted/30 flex flex-col h-full">
      {/* Fixed Header */}
      <div className="p-4 border-b border-border bg-muted/30">
        <h2 className="text-lg font-semibold mb-2">Widget Library</h2>
        <p className="text-sm text-muted-foreground">
          Drag widgets to your dashboard or click to add them.
        </p>
      </div>

      {/* Scrollable Content */}
      <div className="flex-1 overflow-y-auto p-4">
        <div className="space-y-6">
          {categories.map(category => (
            <div key={category}>
              <h3 className="text-sm font-medium text-muted-foreground mb-3 uppercase tracking-wide">
                {category}
              </h3>
              <div className="space-y-2">
                {widgetTypes
                  .filter(widget => widget.category === category)
                  .map(widget => (
                    <Card
                      key={widget.type}
                      className="cursor-pointer hover:shadow-md transition-shadow duration-200 group"
                      onClick={() => onAddWidget(widget.type)}
                      draggable
                      onDragStart={(e) => {
                        e.dataTransfer.setData('application/json', JSON.stringify({
                          type: 'widget',
                          widgetType: widget.type,
                        }));
                      }}
                    >
                      <CardHeader className="pb-3">
                        <div className="flex items-center space-x-3">
                          <div className="text-primary group-hover:text-primary/80 transition-colors">
                            {widget.icon}
                          </div>
                          <div className="flex-1 min-w-0">
                            <CardTitle className="text-sm font-medium truncate">
                              {widget.name}
                            </CardTitle>
                          </div>
                        </div>
                      </CardHeader>
                      <CardContent className="pt-0">
                        <p className="text-xs text-muted-foreground">
                          {widget.description}
                        </p>
                      </CardContent>
                    </Card>
                  ))}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Fixed Footer */}
      <div className="p-4 border-t border-border bg-muted/30">
        <div className="p-4 bg-background rounded-lg border">
          <h4 className="text-sm font-medium mb-2">How to use</h4>
          <div className="text-xs text-muted-foreground space-y-1">
            <p>• Click a widget to add it to your dashboard</p>
            <p>• Drag widgets to position them</p>
            <p>• Use the widget menu to configure or remove widgets</p>
            <p>• Resize widgets by dragging their corners</p>
          </div>
        </div>
      </div>
    </div>
  );
};

export default WidgetLibrary;
