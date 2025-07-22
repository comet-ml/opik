import React, { useState, useEffect } from 'react';
import { X, Save, Trash2, TestTube, CheckCircle, XCircle, Loader, Eye } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Switch } from '@/components/ui/switch';
import { Separator } from '@/components/ui/separator';
import { Tag } from '@/components/ui/tag';
import { DashboardLayout } from '@/types/dashboard';
import { validateWidgetConfig } from '@/utils/validation';
import { chartColorPalettes } from '@/utils/chartHelpers';
import { useDataPreview } from '@/hooks/useDataPreview';

interface ConfigPanelProps {
  widget?: DashboardLayout;
  onClose: () => void;
  onUpdate: (updates: Partial<DashboardLayout>) => void;
}

const ConfigPanel: React.FC<ConfigPanelProps> = ({ widget, onClose, onUpdate }) => {
  const [config, setConfig] = useState(widget?.config || {
    title: '',
    dataSource: '',
    queryParams: {},
    chartOptions: {},
  });
  const [errors, setErrors] = useState<string[]>([]);

  // Data preview functionality
  const dataSource = {
    endpoint: config.dataSource || '',
    method: 'GET' as const,
    queryParams: config.queryParams || {},
    headers: {},
  };
  
  const {
    connectionStatus,
    connectionMessage,
    previewData,
    availableFields,
    testConnection,
    clearPreview,
    getSuggestedFields,
  } = useDataPreview(dataSource);

  useEffect(() => {
    if (widget) {
      setConfig(widget.config);
    }
  }, [widget]);

  const handleSave = () => {
    try {
      validateWidgetConfig(config);
      onUpdate({ config });
      setErrors([]);
    } catch (error) {
      setErrors([error instanceof Error ? error.message : 'Validation failed']);
    }
  };

  const handleConfigChange = (key: string, value: any) => {
    setConfig(prev => ({ ...prev, [key]: value }));
  };

  const handleChartOptionChange = (key: string, value: any) => {
    setConfig(prev => ({
      ...prev,
      chartOptions: { ...prev.chartOptions, [key]: value },
    }));
  };

  const handleQueryParamChange = (key: string, value: any) => {
    setConfig(prev => ({
      ...prev,
      queryParams: { ...prev.queryParams, [key]: value },
    }));
  };

  if (!widget) return null;

  return (
    <div className="w-96 border-l border-border bg-background p-4 overflow-y-auto">
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-lg font-semibold">Configure Widget</h2>
        <Button variant="ghost" size="sm" onClick={onClose}>
          <X className="h-4 w-4" />
        </Button>
      </div>

      {errors.length > 0 && (
        <div className="mb-4 p-3 bg-destructive/10 border border-destructive/20 rounded-lg">
          <div className="text-sm text-destructive">
            {errors.map((error, index) => (
              <div key={index}>{error}</div>
            ))}
          </div>
        </div>
      )}

      <Tabs defaultValue="general" className="space-y-4">
        <TabsList className="grid w-full grid-cols-3">
          <TabsTrigger value="general">General</TabsTrigger>
          <TabsTrigger value="data">Data</TabsTrigger>
          <TabsTrigger value="appearance">Style</TabsTrigger>
        </TabsList>

        <TabsContent value="general" className="space-y-4">
          <Card>
            <CardHeader>
              <CardTitle className="text-sm">Basic Information</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div>
                <Label htmlFor="title">Widget Title</Label>
                <Input
                  id="title"
                  value={config.title}
                  onChange={(e) => handleConfigChange('title', e.target.value)}
                  placeholder="Enter widget title"
                />
              </div>

              <div>
                <Label>Widget Type</Label>
                <div className="text-sm text-muted-foreground bg-muted p-2 rounded">
                  {widget.type.replace('_', ' ').toUpperCase()}
                </div>
              </div>

              <div>
                <Label>Position & Size</Label>
                <div className="grid grid-cols-2 gap-2 mt-1">
                  <div className="text-xs text-muted-foreground">
                    <span className="font-medium">X:</span> {widget.x}
                  </div>
                  <div className="text-xs text-muted-foreground">
                    <span className="font-medium">Y:</span> {widget.y}
                  </div>
                  <div className="text-xs text-muted-foreground">
                    <span className="font-medium">Width:</span> {widget.w}
                  </div>
                  <div className="text-xs text-muted-foreground">
                    <span className="font-medium">Height:</span> {widget.h}
                  </div>
                </div>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="data" className="space-y-4">
          <Card>
            <CardHeader>
              <CardTitle className="text-sm">Data Source</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div>
                <Label htmlFor="dataSource">API Endpoint</Label>
                <Input
                  id="dataSource"
                  value={config.dataSource}
                  onChange={(e) => {
                    handleConfigChange('dataSource', e.target.value);
                    clearPreview(); // Clear preview when endpoint changes
                  }}
                  placeholder="https://api.example.com/data"
                />
              </div>

              <div>
                <Label>Query Parameters</Label>
                <Textarea
                  value={JSON.stringify(config.queryParams, null, 2)}
                  onChange={(e) => {
                    try {
                      const params = JSON.parse(e.target.value);
                      handleConfigChange('queryParams', params);
                      clearPreview(); // Clear preview when params change
                    } catch {
                      // Invalid JSON, ignore
                    }
                  }}
                  placeholder='{\n  "limit": 100,\n  "filter": "status=active"\n}'
                  rows={4}
                />
                <div className="text-xs text-muted-foreground mt-1">
                  Enter JSON format query parameters
                </div>
              </div>

              <div>
                <Label htmlFor="refreshInterval">Refresh Interval (seconds)</Label>
                <Select
                  value={config.refreshInterval?.toString() || '30'}
                  onValueChange={(value) => handleConfigChange('refreshInterval', parseInt(value))}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="10">10 seconds</SelectItem>
                    <SelectItem value="30">30 seconds</SelectItem>
                    <SelectItem value="60">1 minute</SelectItem>
                    <SelectItem value="300">5 minutes</SelectItem>
                    <SelectItem value="900">15 minutes</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              <Separator />

              {/* API Connection Testing */}
              <div>
                <Label>API Connection</Label>
                <div className="flex items-center gap-2 mt-2">
                  <Button 
                    variant="outline" 
                    size="sm"
                    onClick={testConnection}
                    disabled={!config.dataSource || connectionStatus === 'loading'}
                  >
                    {connectionStatus === 'loading' ? (
                      <Loader className="h-4 w-4 mr-2 animate-spin" />
                    ) : (
                      <TestTube className="h-4 w-4 mr-2" />
                    )}
                    Test Connection
                  </Button>
                  
                  <div className="flex items-center gap-2">
                    {connectionStatus === 'success' && <CheckCircle className="h-4 w-4 text-green-500" />}
                    {connectionStatus === 'error' && <XCircle className="h-4 w-4 text-red-500" />}
                    {connectionStatus === 'loading' && <Loader className="h-4 w-4 animate-spin text-blue-500" />}
                    <span className={`text-sm ${
                      connectionStatus === 'success' ? 'text-green-600' : 
                      connectionStatus === 'error' ? 'text-red-600' : 
                      'text-muted-foreground'
                    }`}>
                      {connectionMessage || 'Click "Test Connection" to verify your API'}
                    </span>
                  </div>
                </div>
              </div>

              {/* Data Preview */}
              {previewData && (
                <div>
                  <Label className="flex items-center gap-2">
                    <Eye className="h-4 w-4" />
                    Data Preview
                  </Label>
                  <Card className="mt-2">
                    <CardContent className="p-3">
                      <div className="max-h-40 overflow-auto">
                        <pre className="text-xs text-muted-foreground">
                          {JSON.stringify(previewData, null, 2)}
                        </pre>
                      </div>
                    </CardContent>
                  </Card>
                </div>
              )}

              {/* Field Mapping */}
              {availableFields.length > 0 && (widget?.type === 'line_chart' || widget?.type === 'bar_chart' || widget?.type === 'pie_chart') && (
                <div>
                  <Label>Field Mapping</Label>
                  <div className="grid grid-cols-1 gap-4 mt-2">
                    <div>
                      <Label htmlFor="xAxisField" className="text-xs">X-Axis Field</Label>
                      <Select 
                        value={config.chartOptions?.xAxisKey || ''} 
                        onValueChange={(value) => handleChartOptionChange('xAxisKey', value)}
                      >
                        <SelectTrigger>
                          <SelectValue placeholder="Select X-axis field" />
                        </SelectTrigger>
                        <SelectContent>
                          {getSuggestedFields(widget?.type || '').xAxis.map(field => (
                            <SelectItem key={field.path} value={field.path}>
                                                             <div className="flex items-center gap-2">
                                 <span>{field.name}</span>
                                 <Tag variant="gray" className="text-xs">
                                   {field.type}
                                 </Tag>
                               </div>
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                    
                    <div>
                      <Label htmlFor="yAxisField" className="text-xs">Y-Axis Field</Label>
                      <Select 
                        value={config.chartOptions?.yAxisKey || ''} 
                        onValueChange={(value) => handleChartOptionChange('yAxisKey', value)}
                      >
                        <SelectTrigger>
                          <SelectValue placeholder="Select Y-axis field" />
                        </SelectTrigger>
                        <SelectContent>
                          {getSuggestedFields(widget?.type || '').yAxis.map(field => (
                            <SelectItem key={field.path} value={field.path}>
                                                             <div className="flex items-center gap-2">
                                 <span>{field.name}</span>
                                 <Tag variant="gray" className="text-xs">
                                   {field.type}
                                 </Tag>
                                 {field.sampleValue !== undefined && (
                                   <span className="text-xs text-muted-foreground">
                                     (e.g. {String(field.sampleValue).slice(0, 20)})
                                   </span>
                                 )}
                               </div>
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                  </div>
                </div>
              )}

              {/* KPI Card Value Field */}
              {availableFields.length > 0 && widget?.type === 'kpi_card' && (
                <div>
                  <Label>Value Field</Label>
                  <Select 
                    value={config.chartOptions?.valueKey || ''} 
                    onValueChange={(value) => handleChartOptionChange('valueKey', value)}
                  >
                    <SelectTrigger className="mt-2">
                      <SelectValue placeholder="Select value field" />
                    </SelectTrigger>
                    <SelectContent>
                      {availableFields.filter(f => f.type === 'number' || f.type === 'integer').map(field => (
                        <SelectItem key={field.path} value={field.path}>
                          <div className="flex items-center gap-2">
                            <span>{field.name}</span>
                                                         <Tag variant="gray" className="text-xs">
                               {field.type}
                             </Tag>
                            {field.sampleValue !== undefined && (
                              <span className="text-xs text-muted-foreground">
                                (value: {field.sampleValue})
                              </span>
                            )}
                          </div>
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              )}

              {/* Available Fields Summary */}
              {availableFields.length > 0 && (
                <div>
                  <Label className="text-xs">Available Fields ({availableFields.length})</Label>
                  <div className="mt-2 max-h-32 overflow-auto">
                    <div className="flex flex-wrap gap-1">
                      {availableFields.slice(0, 15).map(field => (
                                                 <Tag key={field.path} variant="default" className="text-xs">
                           {field.name} ({field.type})
                         </Tag>
                      ))}
                      {availableFields.length > 15 && (
                                                 <Tag variant="default" className="text-xs">
                           +{availableFields.length - 15} more...
                         </Tag>
                      )}
                    </div>
                  </div>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="appearance" className="space-y-4">
          <Card>
            <CardHeader>
              <CardTitle className="text-sm">Chart Appearance</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              {widget.type !== 'table' && widget.type !== 'kpi_card' && (
                <>
                  <div>
                    <Label>Color Palette</Label>
                    <Select
                      value={config.chartOptions?.colorPalette || 'default'}
                      onValueChange={(value) => {
                        const colors = chartColorPalettes[value as keyof typeof chartColorPalettes];
                        handleChartOptionChange('colors', colors);
                        handleChartOptionChange('colorPalette', value);
                      }}
                    >
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="default">Default</SelectItem>
                        <SelectItem value="blue">Blue</SelectItem>
                        <SelectItem value="green">Green</SelectItem>
                        <SelectItem value="warm">Warm</SelectItem>
                        <SelectItem value="cool">Cool</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>

                  <div className="flex items-center justify-between">
                    <Label htmlFor="showLegend">Show Legend</Label>
                    <Switch
                      id="showLegend"
                      checked={config.chartOptions?.showLegend !== false}
                      onCheckedChange={(checked) => handleChartOptionChange('showLegend', checked)}
                    />
                  </div>

                  <div className="flex items-center justify-between">
                    <Label htmlFor="showGrid">Show Grid</Label>
                    <Switch
                      id="showGrid"
                      checked={config.chartOptions?.showGrid !== false}
                      onCheckedChange={(checked) => handleChartOptionChange('showGrid', checked)}
                    />
                  </div>
                </>
              )}

              <div>
                <Label htmlFor="height">Chart Height (px)</Label>
                <Input
                  id="height"
                  type="number"
                  value={config.chartOptions?.height || 300}
                  onChange={(e) => handleChartOptionChange('height', parseInt(e.target.value))}
                  min={200}
                  max={800}
                />
              </div>

              {(widget.type === 'line_chart' || widget.type === 'bar_chart') && (
                <>
                  <Separator />
                  <div>
                    <Label htmlFor="xAxisKey">X-Axis Field</Label>
                    <Input
                      id="xAxisKey"
                      value={config.chartOptions?.xAxisKey || ''}
                      onChange={(e) => handleChartOptionChange('xAxisKey', e.target.value)}
                      placeholder="timestamp"
                    />
                  </div>
                  <div>
                    <Label htmlFor="yAxisKey">Y-Axis Field</Label>
                    <Input
                      id="yAxisKey"
                      value={config.chartOptions?.yAxisKey || ''}
                      onChange={(e) => handleChartOptionChange('yAxisKey', e.target.value)}
                      placeholder="value"
                    />
                  </div>
                </>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      <div className="flex items-center justify-between pt-6 border-t">
        <Button variant="outline" onClick={onClose}>
          Cancel
        </Button>
        <Button onClick={handleSave}>
          <Save className="h-4 w-4 mr-2" />
          Save Changes
        </Button>
      </div>
    </div>
  );
};

export default ConfigPanel;
