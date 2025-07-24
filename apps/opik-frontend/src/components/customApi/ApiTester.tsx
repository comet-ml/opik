import React, { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  Play,
  Copy,
  ExternalLink,
  CheckCircle,
  AlertTriangle,
  Clock,
  Code,
  Loader2,
} from 'lucide-react';
import { CustomApiInstance, CustomApiTestResult } from '@/types/customApi';
import customApiService from '@/services/customApiService';

interface ApiTesterProps {
  api: CustomApiInstance;
  testResult?: CustomApiTestResult;
  onTest: () => void;
  onCopyEndpoint: () => void;
  onOpenEndpoint: () => void;
}

const ApiTester: React.FC<ApiTesterProps> = ({
  api,
  testResult,
  onTest,
  onCopyEndpoint,
  onOpenEndpoint,
}) => {
  const [isTestingLocal, setIsTestingLocal] = useState(false);
  const [localTestResult, setLocalTestResult] = useState<CustomApiTestResult | null>(null);

  const handleLocalTest = async () => {
    setIsTestingLocal(true);
    try {
      const result = await customApiService.callInstance(api.instance_name);
      setLocalTestResult(result);
    } catch (error) {
      setLocalTestResult({
        success: false,
        error: `Test failed: ${error instanceof Error ? error.message : 'Unknown error'}`,
      });
    } finally {
      setIsTestingLocal(false);
    }
  };

  const currentResult = localTestResult || testResult;

  const formatJson = (data: any) => {
    try {
      return JSON.stringify(data, null, 2);
    } catch {
      return String(data);
    }
  };

  const copyResult = async () => {
    if (currentResult?.data) {
      try {
        await navigator.clipboard.writeText(formatJson(currentResult.data));
      } catch (error) {
        console.error('Failed to copy result:', error);
      }
    }
  };

  const getStatusIcon = () => {
    if (!currentResult) return null;
    
    if (currentResult.success) {
      return <CheckCircle className="h-5 w-5 text-green-500" />;
    } else {
      return <AlertTriangle className="h-5 w-5 text-red-500" />;
    }
  };

  const getStatusText = () => {
    if (!currentResult) return 'Ready to test';
    
    if (currentResult.success) {
      return `Success (${currentResult.execution_time || 0}ms)`;
    } else {
      return `Failed (${currentResult.execution_time || 0}ms)`;
    }
  };

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center justify-between">
            <span>Test API: {api.instance_name}</span>
            <div className="flex items-center space-x-2">
              {getStatusIcon()}
              <span className="text-sm text-muted-foreground">
                {getStatusText()}
              </span>
            </div>
          </CardTitle>
          <CardDescription>
            Execute your Python code and see the results in real-time
          </CardDescription>
        </CardHeader>
        
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <div className="text-sm font-semibold">API Endpoint:</div>
            <div className="flex items-center space-x-2">
              <div className="flex-1 bg-muted p-3 rounded font-mono text-sm break-all">
                {customApiService.getApiEndpoint(api.instance_name)}
              </div>
              <Button size="sm" variant="outline" onClick={onCopyEndpoint}>
                <Copy className="h-4 w-4" />
              </Button>
              <Button size="sm" variant="outline" onClick={onOpenEndpoint}>
                <ExternalLink className="h-4 w-4" />
              </Button>
            </div>
          </div>

          <div className="flex items-center space-x-2">
            <Button 
              onClick={handleLocalTest} 
              disabled={isTestingLocal}
              className="flex-1"
            >
              {isTestingLocal ? (
                <Loader2 className="h-4 w-4 mr-2 animate-spin" />
              ) : (
                <Play className="h-4 w-4 mr-2" />
              )}
              {isTestingLocal ? 'Testing...' : 'Run Test'}
            </Button>
            <Button onClick={onTest} variant="outline">
              <Play className="h-4 w-4 mr-2" />
              Test via Parent
            </Button>
          </div>
        </CardContent>
      </Card>

      {currentResult && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center justify-between">
              <span>Test Results</span>
              {currentResult.success && (
                <Button size="sm" variant="outline" onClick={copyResult}>
                  <Copy className="h-4 w-4 mr-2" />
                  Copy Result
                </Button>
              )}
            </CardTitle>
          </CardHeader>
          
          <CardContent>
            <Tabs defaultValue="formatted" className="w-full">
              <TabsList className="grid w-full grid-cols-3">
                <TabsTrigger value="formatted">Formatted</TabsTrigger>
                <TabsTrigger value="raw">Raw JSON</TabsTrigger>
                <TabsTrigger value="details">Details</TabsTrigger>
              </TabsList>
              
              <TabsContent value="formatted" className="space-y-4">
                {currentResult.success ? (
                  <Alert>
                    <CheckCircle className="h-4 w-4" />
                    <AlertDescription>
                      <strong>Success!</strong> Your API executed successfully.
                    </AlertDescription>
                  </Alert>
                ) : (
                  <Alert variant="destructive">
                    <AlertTriangle className="h-4 w-4" />
                    <AlertDescription>
                      <strong>Error:</strong> {currentResult.error}
                    </AlertDescription>
                  </Alert>
                )}
                
                {currentResult.success && currentResult.data && (
                  <div className="space-y-2">
                    <div className="text-sm font-semibold">Response Data:</div>
                    <div className="bg-muted p-4 rounded-lg">
                      <pre className="text-sm whitespace-pre-wrap font-mono">
                        {formatJson(currentResult.data)}
                      </pre>
                    </div>
                  </div>
                )}
              </TabsContent>
              
              <TabsContent value="raw" className="space-y-4">
                <div className="space-y-2">
                  <div className="text-sm font-semibold">Raw JSON Response:</div>
                  <div className="bg-muted p-4 rounded-lg">
                    <pre className="text-sm whitespace-pre-wrap font-mono">
                      {formatJson(currentResult)}
                    </pre>
                  </div>
                </div>
              </TabsContent>
              
              <TabsContent value="details" className="space-y-4">
                <div className="grid grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <div className="text-sm font-semibold">Status:</div>
                    <div className="flex items-center space-x-2">
                      {getStatusIcon()}
                      <span className={currentResult.success ? 'text-green-600' : 'text-red-600'}>
                        {currentResult.success ? 'Success' : 'Failed'}
                      </span>
                    </div>
                  </div>
                  
                  <div className="space-y-2">
                    <div className="text-sm font-semibold">Execution Time:</div>
                    <div className="flex items-center space-x-2">
                      <Clock className="h-4 w-4 text-muted-foreground" />
                      <span>{currentResult.execution_time || 0}ms</span>
                    </div>
                  </div>
                  
                  {currentResult.error && (
                    <div className="col-span-2 space-y-2">
                      <div className="text-sm font-semibold text-red-600">Error Details:</div>
                      <div className="bg-red-50 p-3 rounded border border-red-200">
                        <pre className="text-sm text-red-800 whitespace-pre-wrap">
                          {currentResult.error}
                        </pre>
                      </div>
                    </div>
                  )}
                </div>
              </TabsContent>
            </Tabs>
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center space-x-2">
            <Code className="h-5 w-5" />
            <span>Current Python Code</span>
          </CardTitle>
        </CardHeader>
        
        <CardContent>
          <div className="bg-muted p-4 rounded-lg">
            <pre className="text-sm whitespace-pre-wrap font-mono text-muted-foreground">
              {api.code}
            </pre>
          </div>
        </CardContent>
      </Card>

      <Alert>
        <Code className="h-4 w-4" />
        <AlertDescription>
          <strong>Testing Tips:</strong>
          <ul className="list-disc list-inside mt-2 space-y-1">
            <li>The "Run Test" button executes your code directly from this interface</li>
            <li>You can also test by opening the endpoint URL in a new browser tab</li>
            <li>Check the execution time to monitor performance</li>
            <li>Copy the result to use in other applications or tests</li>
          </ul>
        </AlertDescription>
      </Alert>
    </div>
  );
};

export default ApiTester; 