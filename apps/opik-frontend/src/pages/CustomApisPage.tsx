import React, { useState, useEffect } from 'react';
import { Plus, RefreshCw, Code, Play, Edit, Trash2, Copy, ExternalLink, AlertTriangle, CheckCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useToast } from '@/components/ui/use-toast';
import customApiService from '@/services/customApiService';
import { CustomApiInstance, CustomApiTestResult } from '@/types/customApi';
import ApiEditor from '@/components/customApi/ApiEditor';
import ApiTester from '@/components/customApi/ApiTester';
import ApiList from '@/components/customApi/ApiList';

const CustomApisPage: React.FC = () => {
  const [instances, setInstances] = useState<CustomApiInstance[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedApi, setSelectedApi] = useState<CustomApiInstance | null>(null);
  const [activeTab, setActiveTab] = useState<'list' | 'create' | 'edit' | 'test'>('list');
  const [testResults, setTestResults] = useState<Record<string, CustomApiTestResult>>({});
  const [backendAvailable, setBackendAvailable] = useState<boolean | null>(null);
  const { toast } = useToast();

  useEffect(() => {
    loadInstances();
  }, []);

  const loadInstances = async () => {
    try {
      setLoading(true);
      const data = await customApiService.listInstances();
      setInstances(data || []);
      setBackendAvailable(true);
    } catch (error) {
      console.error('Failed to load instances:', error);
      setInstances([]); // Set empty array on error
      setBackendAvailable(false);
      
      // Only show toast if this is not the initial load
      if (backendAvailable !== false) {
        toast({
          title: 'Backend Connection Error',
          description: `Cannot connect to the Custom API backend (port 9080). Please ensure the Python backend service is running.`,
          variant: 'destructive',
        });
      }
    } finally {
      setLoading(false);
    }
  };

  const handleCreateApi = async (instanceName: string, code: string, description?: string) => {
    try {
      const newInstance = await customApiService.createInstance({
        instance_name: instanceName,
        code,
        description,
      });
      setInstances(prev => [...prev, newInstance]);
      setActiveTab('list');
      toast({
        title: 'Success',
        description: `Custom API "${instanceName}" created successfully`,
      });
    } catch (error) {
      toast({
        title: 'Error',
        description: `Failed to create API: ${error instanceof Error ? error.message : 'Unknown error'}`,
        variant: 'destructive',
      });
    }
  };

  const handleUpdateApi = async (instanceName: string, code: string, description?: string) => {
    try {
      const updatedInstance = await customApiService.updateInstance({
        instance_name: instanceName,
        code,
        description,
      });
      setInstances(prev => prev.map(inst => 
        inst.instance_name === instanceName ? updatedInstance : inst
      ));
      setActiveTab('list');
      setSelectedApi(null);
      toast({
        title: 'Success',
        description: `Custom API "${instanceName}" updated successfully`,
      });
    } catch (error) {
      toast({
        title: 'Error',
        description: `Failed to update API: ${error instanceof Error ? error.message : 'Unknown error'}`,
        variant: 'destructive',
      });
    }
  };

  const handleDeleteApi = async (instanceName: string) => {
    try {
      await customApiService.deleteInstance(instanceName);
      setInstances(prev => prev.filter(inst => inst.instance_name !== instanceName));
      toast({
        title: 'Success',
        description: `Custom API "${instanceName}" deleted successfully`,
      });
    } catch (error) {
      toast({
        title: 'Error',
        description: `Failed to delete API: ${error instanceof Error ? error.message : 'Unknown error'}`,
        variant: 'destructive',
      });
    }
  };

  const handleTestApi = async (instanceName: string) => {
    try {
      const result = await customApiService.callInstance(instanceName);
      setTestResults(prev => ({ ...prev, [instanceName]: result }));
      setActiveTab('test');
      setSelectedApi(instances.find(inst => inst.instance_name === instanceName) || null);
    } catch (error) {
      toast({
        title: 'Error',
        description: `Failed to test API: ${error instanceof Error ? error.message : 'Unknown error'}`,
        variant: 'destructive',
      });
    }
  };

  const handleEditApi = (api: CustomApiInstance) => {
    setSelectedApi(api);
    setActiveTab('edit');
  };

  const copyApiEndpoint = async (instanceName: string) => {
    const endpoint = customApiService.getApiEndpoint(instanceName);
    try {
      await navigator.clipboard.writeText(endpoint);
      toast({
        title: 'Success',
        description: 'API endpoint copied to clipboard',
      });
    } catch (error) {
      toast({
        title: 'Error',
        description: 'Failed to copy endpoint to clipboard',
        variant: 'destructive',
      });
    }
  };

  const openApiInNewTab = (instanceName: string) => {
    const endpoint = customApiService.getApiEndpoint(instanceName);
    window.open(endpoint, '_blank');
  };

  return (
    <div className="container mx-auto p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <div className="flex items-center space-x-3">
            <h1 className="text-3xl font-bold">Custom APIs</h1>
            {backendAvailable === false && (
              <div className="flex items-center space-x-1 px-2 py-1 bg-red-100 text-red-800 rounded-md text-xs">
                <AlertTriangle className="h-3 w-3" />
                <span>Backend Offline</span>
              </div>
            )}
            {backendAvailable === true && (
              <div className="flex items-center space-x-1 px-2 py-1 bg-green-100 text-green-800 rounded-md text-xs">
                <CheckCircle className="h-3 w-3" />
                <span>Backend Online</span>
              </div>
            )}
          </div>
          <p className="text-muted-foreground mt-2">
            Create and manage custom API endpoints using Python code
          </p>
        </div>
        <div className="flex items-center space-x-2">
          <Button 
            variant="outline" 
            size="sm" 
            onClick={loadInstances}
            disabled={loading}
          >
            <RefreshCw className={`h-4 w-4 mr-2 ${loading ? 'animate-spin' : ''}`} />
            Refresh
          </Button>
          <Button 
            onClick={() => setActiveTab('create')}
            size="sm"
          >
            <Plus className="h-4 w-4 mr-2" />
            Create API
          </Button>
        </div>
      </div>

      <Tabs value={activeTab} onValueChange={(value) => setActiveTab(value as any)}>
        <TabsList className="grid w-full grid-cols-4">
          <TabsTrigger value="list" className="flex items-center space-x-2">
            <Code className="h-4 w-4" />
            <span>APIs ({instances?.length || 0})</span>
          </TabsTrigger>
          <TabsTrigger value="create" className="flex items-center space-x-2">
            <Plus className="h-4 w-4" />
            <span>Create</span>
          </TabsTrigger>
          <TabsTrigger value="edit" disabled={!selectedApi} className="flex items-center space-x-2">
            <Edit className="h-4 w-4" />
            <span>Edit</span>
          </TabsTrigger>
          <TabsTrigger value="test" disabled={!selectedApi} className="flex items-center space-x-2">
            <Play className="h-4 w-4" />
            <span>Test</span>
          </TabsTrigger>
        </TabsList>

        <TabsContent value="list" className="space-y-4">
          <ApiList
            instances={instances}
            loading={loading}
            onEdit={handleEditApi}
            onDelete={handleDeleteApi}
            onTest={handleTestApi}
            onCopyEndpoint={copyApiEndpoint}
            onOpenEndpoint={openApiInNewTab}
          />
        </TabsContent>

        <TabsContent value="create" className="space-y-4">
          <Card>
            <CardHeader>
              <CardTitle>Create New Custom API</CardTitle>
              <CardDescription>
                Write Python code that will be executed when your custom API endpoint is called.
                The code should assign a result to the `result` variable or use a return statement.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <ApiEditor
                mode="create"
                onSave={handleCreateApi}
                onCancel={() => setActiveTab('list')}
              />
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="edit" className="space-y-4">
          {selectedApi && (
            <Card>
              <CardHeader>
                <CardTitle>Edit API: {selectedApi.instance_name}</CardTitle>
                <CardDescription>
                  Modify the Python code for your custom API endpoint.
                </CardDescription>
              </CardHeader>
              <CardContent>
                <ApiEditor
                  mode="edit"
                  initialData={selectedApi}
                  onSave={handleUpdateApi}
                  onCancel={() => {
                    setActiveTab('list');
                    setSelectedApi(null);
                  }}
                />
              </CardContent>
            </Card>
          )}
        </TabsContent>

        <TabsContent value="test" className="space-y-4">
          {selectedApi && (
            <ApiTester
              api={selectedApi}
              testResult={testResults[selectedApi.instance_name]}
              onTest={() => handleTestApi(selectedApi.instance_name)}
              onCopyEndpoint={() => copyApiEndpoint(selectedApi.instance_name)}
              onOpenEndpoint={() => openApiInNewTab(selectedApi.instance_name)}
            />
          )}
        </TabsContent>
      </Tabs>

      {(!instances || instances.length === 0) && !loading && (
        <Card className="text-center py-12">
          <CardContent>
            {backendAvailable === false ? (
              <>
                <AlertTriangle className="h-12 w-12 mx-auto mb-4 text-red-500" />
                <h3 className="text-lg font-semibold mb-2 text-red-600">Backend Service Unavailable</h3>
                <p className="text-muted-foreground mb-4">
                  Cannot connect to the Custom API backend service on port 9080.<br />
                  Please ensure the Python backend service is running.
                </p>
                <div className="space-y-2">
                  <Button onClick={loadInstances} variant="outline">
                    <RefreshCw className="h-4 w-4 mr-2" />
                    Retry Connection
                  </Button>
                  <p className="text-sm text-muted-foreground">
                    Expected endpoint: <code className="bg-muted px-1 py-0.5 rounded">http://localhost:9080/api</code>
                  </p>
                </div>
              </>
            ) : (
              <>
                <Code className="h-12 w-12 mx-auto mb-4 text-muted-foreground" />
                <h3 className="text-lg font-semibold mb-2">No Custom APIs Yet</h3>
                <p className="text-muted-foreground mb-4">
                  Create your first custom API endpoint by writing Python code
                </p>
                <Button onClick={() => setActiveTab('create')}>
                  <Plus className="h-4 w-4 mr-2" />
                  Create Your First API
                </Button>
              </>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  );
};

export default CustomApisPage; 