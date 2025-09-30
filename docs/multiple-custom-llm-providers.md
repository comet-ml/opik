# Multiple Custom LLM Providers Implementation Plan

## Table of Contents
1. [Problem Statement](#problem-statement)
2. [Current Architecture Analysis](#current-architecture-analysis)
3. [Proposed Solution](#proposed-solution)
4. [Implementation Plan](#implementation-plan)
5. [Technical Specifications](#technical-specifications)
6. [UI/UX Design](#uiux-design)
7. [Testing Strategy](#testing-strategy)
8. [Migration & Backward Compatibility](#migration--backward-compatibility)

---

## Problem Statement

### Current Limitation
The system currently supports only **ONE custom LLM provider per workspace**, which is enforced by:
- Database unique constraint: `UNIQUE (workspace_id, provider)`
- Single `CUSTOM_LLM` enum value
- Single model prefix: `custom-llm/`
- Frontend UI designed for single custom provider

### Business Requirement
Support **multiple distinct custom LLM providers** per workspace (e.g., Ollama, vLLM, LM Studio, etc.) to enable:
- Users to configure multiple OpenAI API-compatible providers
- Each provider with its own base URL, API key, and model list
- Seamless selection of models from different custom providers in Playground and Online Scoring

---

## Current Architecture Analysis

### Database Layer

**Table: `llm_provider_api_key`**
```sql
CREATE TABLE llm_provider_api_key (
    id CHAR(36) NOT NULL,
    provider VARCHAR(250) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    api_key TEXT NOT NULL,
    name VARCHAR(255) DEFAULT NULL,
    base_url VARCHAR(255) DEFAULT NULL,
    headers JSON DEFAULT NULL,
    configuration JSON DEFAULT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    last_updated_at TIMESTAMP(6) NOT NULL,
    last_updated_by VARCHAR(100) NOT NULL,
    
    CONSTRAINT llm_provider_api_key_pk PRIMARY KEY (id),
    CONSTRAINT llm_provider_api_key_workspace_id_provider UNIQUE (workspace_id, provider)
);
```

**Current Constraints:**
- ✅ Multiple providers per workspace (OpenAI, Anthropic, etc.)
- ❌ Only ONE `CUSTOM_LLM` per workspace due to unique constraint

### Backend Architecture

**Key Files:**
- `LlmProvider.java` - Enum with `CUSTOM_LLM` value
- `CustomLlmModelNameChecker.java` - Checks for `custom-llm/` prefix
- `LlmProviderFactoryImpl.java` - Resolves provider from model name
- `CustomLlmProvider.java` - OpenAI API-compatible wrapper

**Current Flow:**
1. Model name: `custom-llm/llama-3.2`
2. Provider resolution: Detects `custom-llm/` prefix → returns `CUSTOM_LLM`
3. Config lookup: Finds **single** custom provider config for workspace
4. API call: Uses base_url + model name (strips prefix)

### Frontend Architecture

**Key Files:**
- `ManageAIProviderDialog.tsx` - Provider configuration UI
- `CustomProviderDetails.tsx` - Custom provider form fields
- `useCustomProviderModels.ts` - Fetches models for custom provider
- `constants/providers.ts` - Provider type definitions

**Current UI:**
- Dropdown with provider types (OpenAI, Anthropic, **vLLM / Custom provider**)
- "Configured" badge for existing providers
- Single custom provider slot

---

## Proposed Solution

### Core Concept

**Singleton Providers** (unchanged):
- OpenAI, Anthropic, Gemini, etc.
- One configuration per workspace
- Selected via dropdown

**Multi-Instance Providers** (new):
- Custom LLM providers
- Multiple configurations per workspace
- Each with unique `provider_name`
- Managed via separate "Custom Providers" section

### Key Changes

1. **Database**: Add `provider_name` column + compound unique constraint
2. **Backend**: Support `provider_name` in provider resolution
3. **Frontend**: Separate "Custom Providers" section with add/edit/delete
4. **Model Naming**: Use provider name in prefix: `{provider_name}/model-name`

---

## Implementation Plan

### Phase 1: Database Migration

#### Task 1.1: Create Migration Script
**File:** `apps/opik-backend/src/main/resources/liquibase/db-app-state/migrations/000XXX_add_provider_name_column.sql`

```sql
--liquibase formatted sql
--changeset [author]:add_provider_name_to_llm_provider_api_key

-- Add provider_name column (nullable for backward compatibility)
ALTER TABLE llm_provider_api_key 
    ADD COLUMN provider_name VARCHAR(100) DEFAULT NULL;

-- Drop old unique constraint
ALTER TABLE llm_provider_api_key 
    DROP INDEX llm_provider_api_key_workspace_id_provider;

-- Add new compound unique constraint
-- For non-custom providers: (workspace_id, provider) must be unique
-- For custom providers: (workspace_id, provider, provider_name) must be unique
ALTER TABLE llm_provider_api_key 
    ADD CONSTRAINT llm_provider_api_key_workspace_provider_name 
    UNIQUE (workspace_id, provider, provider_name);

-- For backward compatibility: set provider_name = 'default' for existing custom providers
UPDATE llm_provider_api_key 
SET provider_name = 'default' 
WHERE provider = 'custom-llm' AND provider_name IS NULL;

```

**Acceptance Criteria:**
- ✅ Migration runs without errors
- ✅ Existing data preserved
- ✅ Old custom provider gets `provider_name = 'default'`
- ✅ Can insert multiple custom providers with different names

**Estimated Time:** 2 hours

---

#### Task 1.2: Update DAO Layer
**File:** `apps/opik-backend/src/main/java/com/comet/opik/domain/LlmProviderApiKeyDAO.java`

```java
@RegisterConstructorMapper(ProviderApiKey.class)
public interface LlmProviderApiKeyDAO {

    // Updated: Add provider_name to INSERT
    @SqlUpdate("INSERT INTO llm_provider_api_key " +
               "(id, provider, provider_name, workspace_id, api_key, name, " +
               "created_by, last_updated_by, headers, base_url, configuration) " +
               "VALUES (:bean.id, :bean.provider, :bean.providerName, :workspaceId, " +
               ":bean.apiKey, :bean.name, :bean.createdBy, :bean.lastUpdatedBy, " +
               ":bean.headers, :bean.baseUrl, :bean.configuration)")
    void save(@Bind("workspaceId") String workspaceId,
              @BindMethods("bean") ProviderApiKey providerApiKey);

    // Updated: Add provider_name to UPDATE WHERE clause
    @SqlUpdate("UPDATE llm_provider_api_key SET " +
               "api_key = CASE WHEN :bean.apiKey IS NULL THEN api_key ELSE :bean.apiKey END, " +
               "name = CASE WHEN :bean.name IS NULL THEN name ELSE :bean.name END, " +
               "headers = CASE WHEN :bean.headers IS NULL THEN headers ELSE :bean.headers END, " +
               "base_url = CASE WHEN :bean.baseUrl IS NULL THEN base_url ELSE :bean.baseUrl END, " +
               "configuration = CASE WHEN :bean.configuration IS NULL THEN configuration ELSE :bean.configuration END, " +
               "last_updated_by = :lastUpdatedBy " +
               "WHERE id = :id AND workspace_id = :workspaceId")
    void update(@Bind("id") UUID id,
                @Bind("workspaceId") String workspaceId,
                @Bind("lastUpdatedBy") String lastUpdatedBy,
                @BindMethods("bean") ProviderApiKeyUpdate providerApiKeyUpdate);

    // New: Find by provider and provider_name
    @SqlQuery("SELECT * FROM llm_provider_api_key " +
              "WHERE workspace_id = :workspaceId " +
              "AND provider = :provider " +
              "AND (:providerName IS NULL OR provider_name = :providerName)")
    ProviderApiKey findByProviderAndName(
        @Bind("workspaceId") String workspaceId,
        @Bind("provider") LlmProvider provider,
        @Bind("providerName") String providerName
    );

    // Updated: Find all (includes provider_name)
    @SqlQuery("SELECT * FROM llm_provider_api_key WHERE workspace_id = :workspaceId")
    List<ProviderApiKey> find(@Bind("workspaceId") String workspaceId);

    // Updated: Delete (includes provider_name in WHERE clause if needed)
    @SqlUpdate("DELETE FROM llm_provider_api_key WHERE id IN (<ids>) AND workspace_id = :workspaceId")
    void delete(@BindList("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);
}
```

**Acceptance Criteria:**
- ✅ `provider_name` included in all CRUD operations
- ✅ `findByProviderAndName()` works correctly
- ✅ Backward compatible with existing queries

**Estimated Time:** 3 hours

---

### Phase 2: Backend Domain Model Updates

#### Task 2.1: Update ProviderApiKey DTO
**File:** `apps/opik-backend/src/main/java/com/comet/opik/api/ProviderApiKey.java`

```java
public record ProviderApiKey(
    UUID id,
    LlmProvider provider,
    String providerName,  // NEW FIELD
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    String apiKey,
    String name,
    Map<String, String> headers,
    String baseUrl,
    Map<String, Object> configuration,
    Instant createdAt,
    String createdBy,
    Instant lastUpdatedAt,
    String lastUpdatedBy
) {
    // Builder pattern, validation, etc.
}
```

**Acceptance Criteria:**
- ✅ `providerName` field added with proper JSON serialization
- ✅ Validation ensures `providerName` is required for `CUSTOM_LLM`
- ✅ Backward compatible with existing API responses

**Estimated Time:** 2 hours

---

#### Task 2.2: Update Model Name Resolution
**File:** `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/llm/customllm/CustomLlmModelNameChecker.java`

```java
public class CustomLlmModelNameChecker {
    
    private static final String CUSTOM_LLM_SEPARATOR = "/";
    
    public static boolean isCustomLlmModel(String model) {
        return model != null && model.contains(CUSTOM_LLM_SEPARATOR);
    }
    
    /**
     * Extracts provider name from model string.
     * Example: "ollama/llama-3.2" → "ollama"
     */
    public static String extractProviderName(String model) {
        if (!isCustomLlmModel(model)) {
            throw new IllegalArgumentException("Not a custom LLM model: " + model);
        }
        return model.substring(0, model.indexOf(CUSTOM_LLM_SEPARATOR));
    }
    
    /**
     * Extracts actual model name (strips provider prefix).
     * Example: "ollama/llama-3.2" → "llama-3.2"
     */
    public static String extractModelName(String model) {
        if (!isCustomLlmModel(model)) {
            return model;
        }
        return model.substring(model.indexOf(CUSTOM_LLM_SEPARATOR) + 1);
    }
    
    /**
     * Builds full model identifier.
     * Example: ("ollama", "llama-3.2") → "ollama/llama-3.2"
     */
    public static String buildModelIdentifier(String providerName, String modelName) {
        return providerName + CUSTOM_LLM_SEPARATOR + modelName;
    }
}
```

**Acceptance Criteria:**
- ✅ Correctly extracts provider name from model string
- ✅ Correctly extracts model name (strips prefix)
- ✅ Handles edge cases (null, empty, invalid format)

**Estimated Time:** 2 hours

---

#### Task 2.3: Update LlmProviderFactory
**File:** `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/llm/LlmProviderFactoryImpl.java`

```java
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class LlmProviderFactoryImpl implements LlmProviderFactory {
    
    // Updated: Now requires provider name for custom providers
    @Override
    public ChatLanguageModel getChatLanguageModel(
            @NonNull String workspaceId,
            @NonNull String model) {
        
        LlmProvider provider = getLlmProvider(model);
        String providerName = null;
        
        // Extract provider name for custom LLMs
        if (provider == LlmProvider.CUSTOM_LLM) {
            providerName = CustomLlmModelNameChecker.extractProviderName(model);
        }
        
        ProviderApiKey providerApiKey = getProviderApiKey(workspaceId, provider, providerName);
        LlmServiceProvider serviceProvider = providersMap.get(provider);
        
        // Pass actual model name (without provider prefix)
        String actualModelName = provider == LlmProvider.CUSTOM_LLM
            ? CustomLlmModelNameChecker.extractModelName(model)
            : model;
        
        return serviceProvider.getChatLanguageModel(providerApiKey, actualModelName);
    }
    
    // Updated: Now accepts optional provider name
    private ProviderApiKey getProviderApiKey(
            @NonNull String workspaceId,
            @NonNull LlmProvider provider,
            String providerName) {
        
        // For custom providers, require provider name
        if (provider == LlmProvider.CUSTOM_LLM && providerName == null) {
            throw new BadRequestException(
                "Provider name is required for custom LLM models. " +
                "Expected format: '{provider-name}/model-name'"
            );
        }
        
        // For non-custom providers, find by provider only
        if (provider != LlmProvider.CUSTOM_LLM) {
            return llmProviderApiKeyService.find(workspaceId).content().stream()
                .filter(key -> provider.equals(key.provider()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException(
                    "API key not configured for LLM provider '%s'".formatted(provider.getValue())
                ));
        }
        
        // For custom providers, find by provider AND provider name
        return llmProviderApiKeyService.findByProviderAndName(workspaceId, provider, providerName)
            .orElseThrow(() -> new BadRequestException(
                "Custom LLM provider '%s' not configured in workspace".formatted(providerName)
            ));
    }
}
```

**Acceptance Criteria:**
- ✅ Correctly resolves custom provider by name from model string
- ✅ Passes stripped model name to provider
- ✅ Throws meaningful errors for invalid model formats
- ✅ Backward compatible with non-custom providers

**Estimated Time:** 4 hours

---

#### Task 2.4: Update Service Layer
**File:** `apps/opik-backend/src/main/java/com/comet/opik/domain/LlmProviderApiKeyService.java`

```java
public interface LlmProviderApiKeyService {
    
    ProviderApiKey.ProviderApiKeyPage find(@NonNull String workspaceId);
    
    // New: Find specific custom provider by name
    Optional<ProviderApiKey> findByProviderAndName(
        @NonNull String workspaceId,
        @NonNull LlmProvider provider,
        String providerName
    );
    
    ProviderApiKey save(@NonNull String workspaceId, @NonNull ProviderApiKey providerApiKey);
    
    void update(@NonNull UUID id, @NonNull String workspaceId,
                @NonNull String lastUpdatedBy, @NonNull ProviderApiKeyUpdate providerApiKeyUpdate);
    
    void delete(@NonNull String workspaceId, @NonNull Set<UUID> ids);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class LlmProviderApiKeyServiceImpl implements LlmProviderApiKeyService {
    
    @Override
    public Optional<ProviderApiKey> findByProviderAndName(
            @NonNull String workspaceId,
            @NonNull LlmProvider provider,
            String providerName) {
        
        return template.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(LlmProviderApiKeyDAO.class);
            return Optional.ofNullable(
                repository.findByProviderAndName(workspaceId, provider, providerName)
            );
        });
    }
}
```

**Acceptance Criteria:**
- ✅ New method `findByProviderAndName()` implemented
- ✅ Works correctly for both custom and non-custom providers
- ✅ Returns `Optional` for safe null handling

**Estimated Time:** 2 hours

---

### Phase 3: Frontend UI Updates

#### Task 3.1: Update Provider Constants
**File:** `apps/opik-frontend/src/constants/providers.ts`

```typescript
export const PROVIDER_TYPE = {
  OPENAI: "openai",
  ANTHROPIC: "anthropic",
  GEMINI: "gemini",
  VERTEX_AI: "vertex-ai",
  OPENROUTER: "openrouter",
  // Note: CUSTOM is still used for type checking, but UI handles it differently
  CUSTOM: "custom-llm",
} as const;

// New: Custom provider types (can be extended)
export const CUSTOM_PROVIDER_TYPES = [
  "ollama",
  "vllm",
  "lm-studio",
  "other", // For user-defined names
] as const;

// Model prefix format: {provider_name}/model-name
export const buildCustomModelId = (providerName: string, modelName: string) =>
  `${providerName}/${modelName}`;

export const parseCustomModelId = (modelId: string) => {
  const parts = modelId.split("/");
  if (parts.length < 2) return null;
  return {
    providerName: parts[0],
    modelName: parts.slice(1).join("/"),
  };
};
```

**Acceptance Criteria:**
- ✅ Helper functions for building/parsing model IDs
- ✅ Constants defined for common custom provider types

**Estimated Time:** 1 hour

---

#### Task 3.2: Update Provider Form Schema
**File:** `apps/opik-frontend/src/components/pages-shared/llm/ManageAIProviderDialog/schema.ts`

```typescript
// New: Custom provider form includes provider_name
export const CustomProviderDetailsFormSchema = z.object({
  provider: z.literal(PROVIDER_TYPE.CUSTOM),
  providerName: z
    .string()
    .min(1, { message: "Provider name is required" })
    .max(100, { message: "Provider name cannot exceed 100 characters" })
    .regex(/^[a-z0-9-]+$/, {
      message: "Provider name can only contain lowercase letters, numbers, and hyphens",
    }),
  apiKey: z.string().optional(), // Optional for some providers
  url: z.string().url({ message: "Valid URL is required" }),
  models: z
    .string()
    .min(1, { message: "At least one model is required" })
    .refine(
      (models) => {
        const modelsArray = models.split(",").map((m) => m.trim());
        return modelsArray.length === uniq(modelsArray).length;
      },
      { message: "All model names must be unique" },
    ),
  displayName: z
    .string()
    .min(1, { message: "Display name is required" })
    .max(100, { message: "Display name cannot exceed 100 characters" }),
});

export type CustomProviderDetailsForm = z.infer<typeof CustomProviderDetailsFormSchema>;
```

**Acceptance Criteria:**
- ✅ `providerName` field added with validation
- ✅ Regex ensures valid identifier format
- ✅ `displayName` field for UI display

**Estimated Time:** 2 hours

---

#### Task 3.3: Create Custom Provider Management Section
**File:** `apps/opik-frontend/src/components/pages-shared/llm/CustomProvidersSection.tsx` (NEW)

```typescript
import React from "react";
import { Plus, Pencil, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";

interface CustomProvider {
  id: string;
  providerName: string;
  displayName: string;
  baseUrl: string;
  models: string[];
  isConfigured: boolean;
}

interface CustomProvidersSectionProps {
  customProviders: CustomProvider[];
  onAddNew: () => void;
  onEdit: (provider: CustomProvider) => void;
  onDelete: (providerId: string) => void;
}

export const CustomProvidersSection: React.FC<CustomProvidersSectionProps> = ({
  customProviders,
  onAddNew,
  onEdit,
  onDelete,
}) => {
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle>Custom LLM Providers</CardTitle>
        <Button onClick={onAddNew} size="sm">
          <Plus className="mr-2 h-4 w-4" />
          Add Custom Provider
        </Button>
      </CardHeader>
      <CardContent>
        {customProviders.length === 0 ? (
          <div className="text-center py-8 text-muted-foreground">
            <p>No custom providers configured yet.</p>
            <p className="text-sm mt-2">
              Add your first custom provider to use Ollama, vLLM, or other OpenAI-compatible services.
            </p>
          </div>
        ) : (
          <div className="space-y-4">
            {customProviders.map((provider) => (
              <div
                key={provider.id}
                className="flex items-center justify-between p-4 border rounded-lg"
              >
                <div className="flex-1">
                  <div className="flex items-center gap-2 mb-1">
                    <h4 className="font-medium">{provider.displayName}</h4>
                    <Badge variant="secondary" className="text-xs">
                      {provider.providerName}
                    </Badge>
                  </div>
                  <p className="text-sm text-muted-foreground">{provider.baseUrl}</p>
                  <p className="text-xs text-muted-foreground mt-1">
                    {provider.models.length} model{provider.models.length !== 1 ? "s" : ""}
                  </p>
                </div>
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => onEdit(provider)}
                  >
                    <Pencil className="h-4 w-4" />
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => onDelete(provider.id)}
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
};
```

**Acceptance Criteria:**
- ✅ Displays list of all configured custom providers
- ✅ "Add Custom Provider" button always visible
- ✅ Edit and Delete buttons for each provider
- ✅ Shows provider name, display name, URL, and model count
- ✅ Empty state when no providers configured

**Estimated Time:** 4 hours

---

#### Task 3.4: Update Custom Provider Dialog
**File:** `apps/opik-frontend/src/components/pages-shared/llm/CustomProviderDialog.tsx` (NEW)

```typescript
import React from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Form,
  FormField,
  FormItem,
  FormLabel,
  FormControl,
  FormMessage,
  FormDescription,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { CustomProviderDetailsFormSchema } from "./schema";

interface CustomProviderDialogProps {
  open: boolean;
  onClose: () => void;
  onSave: (data: CustomProviderDetailsForm) => void;
  existingProvider?: CustomProviderDetailsForm;
  mode: "create" | "edit";
}

export const CustomProviderDialog: React.FC<CustomProviderDialogProps> = ({
  open,
  onClose,
  onSave,
  existingProvider,
  mode,
}) => {
  const form = useForm<CustomProviderDetailsForm>({
    resolver: zodResolver(CustomProviderDetailsFormSchema),
    defaultValues: existingProvider || {
      provider: "custom-llm",
      providerName: "",
      displayName: "",
      apiKey: "",
      url: "",
      models: "",
    },
  });

  const handleSubmit = (data: CustomProviderDetailsForm) => {
    onSave(data);
    onClose();
  };

  return (
    <Dialog open={open} onOpenChange={onClose}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>
            {mode === "create" ? "Add Custom Provider" : "Edit Custom Provider"}
          </DialogTitle>
        </DialogHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit(handleSubmit)} className="space-y-4">
            {/* Provider Name Field */}
            <FormField
              control={form.control}
              name="providerName"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Provider Name *</FormLabel>
                  <FormControl>
                    <Input
                      {...field}
                      placeholder="ollama"
                      disabled={mode === "edit"} // Can't change provider name after creation
                    />
                  </FormControl>
                  <FormDescription>
                    Unique identifier for this provider (lowercase, hyphens allowed).
                    This will be used in model selection (e.g., "ollama/llama-3.2").
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            {/* Display Name Field */}
            <FormField
              control={form.control}
              name="displayName"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Display Name *</FormLabel>
                  <FormControl>
                    <Input {...field} placeholder="My Ollama Server" />
                  </FormControl>
                  <FormDescription>
                    Friendly name shown in the UI.
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            {/* Base URL Field */}
            <FormField
              control={form.control}
              name="url"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Base URL *</FormLabel>
                  <FormControl>
                    <Input
                      {...field}
                      placeholder="http://localhost:11434/v1"
                    />
                  </FormControl>
                  <FormDescription>
                    OpenAI-compatible API endpoint.
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            {/* API Key Field */}
            <FormField
              control={form.control}
              name="apiKey"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>API Key</FormLabel>
                  <FormControl>
                    <Input
                      {...field}
                      type="password"
                      placeholder="Optional for local providers"
                    />
                  </FormControl>
                  <FormDescription>
                    Required for cloud providers, optional for local installations.
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            {/* Models Field */}
            <FormField
              control={form.control}
              name="models"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Models *</FormLabel>
                  <FormControl>
                    <Input
                      {...field}
                      placeholder="llama-3.2, codellama, mistral"
                    />
                  </FormControl>
                  <FormDescription>
                    Comma-separated list of model names available on this provider.
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            {/* Action Buttons */}
            <div className="flex justify-end gap-2 pt-4">
              <Button type="button" variant="outline" onClick={onClose}>
                Cancel
              </Button>
              <Button type="submit">
                {mode === "create" ? "Add Provider" : "Save Changes"}
              </Button>
            </div>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
};
```

**Acceptance Criteria:**
- ✅ Form validation with Zod schema
- ✅ Provider name immutable in edit mode
- ✅ All fields properly labeled with descriptions
- ✅ Submit creates/updates provider configuration

**Estimated Time:** 5 hours

---

#### Task 3.5: Update Model Selection Hook
**File:** `apps/opik-frontend/src/hooks/useCustomProviderModels.ts`

```typescript
import { useMemo } from "react";
import { useProviderKeys } from "@/api/provider-keys/useProviderKeys";
import { buildCustomModelId } from "@/constants/providers";

export interface CustomProviderModel {
  id: string; // Format: "provider-name/model-name"
  name: string; // Display name
  providerName: string;
  providerDisplayName: string;
}

export const useCustomProviderModels = (workspaceName: string) => {
  const { data: providerKeys } = useProviderKeys({ workspaceName });

  const customModels = useMemo(() => {
    if (!providerKeys) return [];

    const models: CustomProviderModel[] = [];

    // Find all custom providers
    const customProviders = providerKeys.filter(
      (key) => key.provider === "custom-llm"
    );

    // Extract models from each custom provider
    customProviders.forEach((provider) => {
      const modelsList = provider.configuration?.models || [];
      const providerName = provider.providerName;
      const displayName = provider.displayName || providerName;

      modelsList.forEach((modelName: string) => {
        models.push({
          id: buildCustomModelId(providerName, modelName),
          name: `${displayName} - ${modelName}`,
          providerName,
          providerDisplayName: displayName,
        });
      });
    });

    return models;
  }, [providerKeys]);

  return { customModels, isLoading: !providerKeys };
};
```

**Acceptance Criteria:**
- ✅ Returns flat list of all models from all custom providers
- ✅ Each model has format `provider-name/model-name`
- ✅ Display name includes provider and model name

**Estimated Time:** 3 hours

---

### Phase 4: API Integration

#### Task 4.1: Update Provider Keys API
**File:** `apps/opik-backend/src/main/java/com/comet/opik/api/resources/v1/priv/LlmProviderApiKeyResource.java`

```java
@Path("/v1/private/llm-provider-keys")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class LlmProviderApiKeyResource {
    
    private final @NonNull LlmProviderApiKeyService service;
    
    @GET
    @Operation(summary = "Get all provider API keys for workspace")
    public Response getProviderKeys(
            @Parameter(description = "Workspace name") @HeaderParam(HttpHeaders.WORKSPACE_HEADER) String workspaceName) {
        
        var page = service.find(workspaceName);
        return Response.ok(page).build();
    }
    
    @POST
    @Operation(summary = "Create or update provider API key")
    public Response createOrUpdateProviderKey(
            @Parameter(description = "Workspace name") @HeaderParam(HttpHeaders.WORKSPACE_HEADER) String workspaceName,
            @Valid ProviderApiKey providerApiKey) {
        
        // For custom providers, validate provider_name is present
        if (providerApiKey.provider() == LlmProvider.CUSTOM_LLM) {
            if (providerApiKey.providerName() == null || providerApiKey.providerName().isBlank()) {
                throw new BadRequestException("Provider name is required for custom LLM providers");
            }
        }
        
        var saved = service.save(workspaceName, providerApiKey);
        return Response.status(Response.Status.CREATED).entity(saved).build();
    }
    
    @DELETE
    @Path("/{id}")
    @Operation(summary = "Delete provider API key")
    public Response deleteProviderKey(
            @Parameter(description = "Workspace name") @HeaderParam(HttpHeaders.WORKSPACE_HEADER) String workspaceName,
            @PathParam("id") UUID id) {
        
        service.delete(workspaceName, Set.of(id));
        return Response.noContent().build();
    }
}
```

**Acceptance Criteria:**
- ✅ GET returns all providers (including multiple custom ones)
- ✅ POST validates `provider_name` for custom providers
- ✅ DELETE removes specific custom provider

**Estimated Time:** 3 hours

---

### Phase 5: Testing

#### Task 5.1: Backend Unit Tests

**File:** `apps/opik-backend/src/test/java/com/comet/opik/domain/LlmProviderApiKeyServiceTest.java` (NEW)

```java
@ExtendWith(MockitoExtension.class)
class LlmProviderApiKeyServiceTest {
    
    @Test
    void shouldSaveMultipleCustomProviders() {
        // Given
        var workspace = "test-workspace";
        var ollamaProvider = ProviderApiKey.builder()
            .provider(LlmProvider.CUSTOM_LLM)
            .providerName("ollama")
            .baseUrl("http://localhost:11434/v1")
            .configuration(Map.of("models", List.of("llama-3.2", "codellama")))
            .build();
        
        var vllmProvider = ProviderApiKey.builder()
            .provider(LlmProvider.CUSTOM_LLM)
            .providerName("vllm")
            .baseUrl("http://localhost:8000/v1")
            .configuration(Map.of("models", List.of("mistral")))
            .build();
        
        // When
        service.save(workspace, ollamaProvider);
        service.save(workspace, vllmProvider);
        
        // Then
        var providers = service.find(workspace).content();
        assertThat(providers).hasSize(2);
        assertThat(providers).anyMatch(p -> "ollama".equals(p.providerName()));
        assertThat(providers).anyMatch(p -> "vllm".equals(p.providerName()));
    }
    
    @Test
    void shouldThrowExceptionForDuplicateProviderName() {
        // Given
        var workspace = "test-workspace";
        var provider1 = createCustomProvider("ollama");
        var provider2 = createCustomProvider("ollama"); // Same name
        
        // When
        service.save(workspace, provider1);
        
        // Then
        assertThatThrownBy(() -> service.save(workspace, provider2))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already exists");
    }
    
    @Test
    void shouldFindCustomProviderByName() {
        // Given
        var workspace = "test-workspace";
        var provider = createCustomProvider("ollama");
        service.save(workspace, provider);
        
        // When
        var found = service.findByProviderAndName(workspace, LlmProvider.CUSTOM_LLM, "ollama");
        
        // Then
        assertThat(found).isPresent();
        assertThat(found.get().providerName()).isEqualTo("ollama");
    }
}
```

**Test Coverage:**
- ✅ Save multiple custom providers
- ✅ Prevent duplicate provider names
- ✅ Find custom provider by name
- ✅ Update custom provider
- ✅ Delete custom provider

**Estimated Time:** 6 hours

---

#### Task 5.2: Backend Integration Tests

**File:** `apps/opik-backend/src/test/java/com/comet/opik/infrastructure/llm/LlmProviderFactoryImplTest.java`

```java
class LlmProviderFactoryImplTest {
    
    @Test
    void shouldResolveCustomProviderByModelPrefix() {
        // Given
        var model = "ollama/llama-3.2";
        
        // When
        var provider = factory.getLlmProvider(model);
        
        // Then
        assertThat(provider).isEqualTo(LlmProvider.CUSTOM_LLM);
    }
    
    @Test
    void shouldExtractProviderNameFromModel() {
        // Given
        var model = "ollama/llama-3.2";
        
        // When
        var providerName = CustomLlmModelNameChecker.extractProviderName(model);
        var modelName = CustomLlmModelNameChecker.extractModelName(model);
        
        // Then
        assertThat(providerName).isEqualTo("ollama");
        assertThat(modelName).isEqualTo("llama-3.2");
    }
    
    @Test
    void shouldGetChatLanguageModelForCustomProvider() {
        // Given
        setupCustomProvider("ollama", "http://localhost:11434/v1", List.of("llama-3.2"));
        var model = "ollama/llama-3.2";
        
        // When
        var chatModel = factory.getChatLanguageModel(workspaceId, model);
        
        // Then
        assertThat(chatModel).isNotNull();
        // Verify correct base URL and model name used
    }
    
    @Test
    void shouldThrowExceptionForUnconfiguredCustomProvider() {
        // Given
        var model = "unconfigured-provider/some-model";
        
        // When & Then
        assertThatThrownBy(() -> factory.getChatLanguageModel(workspaceId, model))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("not configured");
    }
}
```

**Test Coverage:**
- ✅ Provider resolution from model string
- ✅ Model name parsing
- ✅ Chat model creation for custom providers
- ✅ Error handling for unconfigured providers

**Estimated Time:** 6 hours

---

#### Task 5.3: Frontend Component Tests

**File:** `apps/opik-frontend/src/components/pages-shared/llm/CustomProvidersSection.test.tsx`

```typescript
import { render, screen, fireEvent } from "@testing-library/react";
import { CustomProvidersSection } from "./CustomProvidersSection";

describe("CustomProvidersSection", () => {
  const mockProviders = [
    {
      id: "1",
      providerName: "ollama",
      displayName: "My Ollama",
      baseUrl: "http://localhost:11434/v1",
      models: ["llama-3.2", "codellama"],
      isConfigured: true,
    },
    {
      id: "2",
      providerName: "vllm",
      displayName: "vLLM Server",
      baseUrl: "http://localhost:8000/v1",
      models: ["mistral"],
      isConfigured: true,
    },
  ];

  it("should display all custom providers", () => {
    render(
      <CustomProvidersSection
        customProviders={mockProviders}
        onAddNew={jest.fn()}
        onEdit={jest.fn()}
        onDelete={jest.fn()}
      />
    );

    expect(screen.getByText("My Ollama")).toBeInTheDocument();
    expect(screen.getByText("vLLM Server")).toBeInTheDocument();
    expect(screen.getByText("2 models")).toBeInTheDocument();
  });

  it("should call onAddNew when Add button clicked", () => {
    const onAddNew = jest.fn();
    render(
      <CustomProvidersSection
        customProviders={mockProviders}
        onAddNew={onAddNew}
        onEdit={jest.fn()}
        onDelete={jest.fn()}
      />
    );

    fireEvent.click(screen.getByText("Add Custom Provider"));
    expect(onAddNew).toHaveBeenCalled();
  });

  it("should show empty state when no providers", () => {
    render(
      <CustomProvidersSection
        customProviders={[]}
        onAddNew={jest.fn()}
        onEdit={jest.fn()}
        onDelete={jest.fn()}
      />
    );

    expect(screen.getByText(/No custom providers configured/)).toBeInTheDocument();
  });
});
```

**Test Coverage:**
- ✅ Display provider list
- ✅ Add new provider
- ✅ Edit existing provider
- ✅ Delete provider
- ✅ Empty state

**Estimated Time:** 4 hours

---

#### Task 5.4: End-to-End Tests

**File:** `apps/opik-frontend/e2e/custom-providers.spec.ts`

```typescript
import { test, expect } from "@playwright/test";

test.describe("Custom Providers", () => {
  test("should add, edit, and delete custom provider", async ({ page }) => {
    // Navigate to providers page
    await page.goto("/providers");

    // Click "Add Custom Provider"
    await page.click('button:has-text("Add Custom Provider")');

    // Fill in provider details
    await page.fill('input[name="providerName"]', "ollama");
    await page.fill('input[name="displayName"]', "My Ollama Server");
    await page.fill('input[name="url"]', "http://localhost:11434/v1");
    await page.fill('input[name="models"]', "llama-3.2, codellama");

    // Submit
    await page.click('button:has-text("Add Provider")');

    // Verify provider appears in list
    await expect(page.locator('text=My Ollama Server')).toBeVisible();

    // Edit provider
    await page.click('button[aria-label="Edit ollama"]');
    await page.fill('input[name="displayName"]', "Updated Ollama");
    await page.click('button:has-text("Save Changes")');

    // Verify update
    await expect(page.locator('text=Updated Ollama')).toBeVisible();

    // Delete provider
    await page.click('button[aria-label="Delete ollama"]');
    await page.click('button:has-text("Confirm")');

    // Verify deletion
    await expect(page.locator('text=Updated Ollama')).not.toBeVisible();
  });

  test("should use custom provider model in playground", async ({ page }) => {
    // Setup: Add custom provider first
    await setupCustomProvider(page, "ollama", "llama-3.2");

    // Navigate to playground
    await page.goto("/playground");

    // Select custom model
    await page.click('button:has-text("Select Model")');
    await page.click('text=ollama/llama-3.2');

    // Verify selection
    await expect(page.locator('text=ollama/llama-3.2')).toBeVisible();

    // Test prompt execution
    await page.fill('textarea[placeholder="Enter prompt"]', "Hello");
    await page.click('button:has-text("Run")');

    // Verify execution (mock response)
    await expect(page.locator('.response-output')).toBeVisible();
  });
});
```

**Test Coverage:**
- ✅ Complete CRUD flow for custom providers
- ✅ Model selection in playground
- ✅ Model selection in online scoring

**Estimated Time:** 8 hours

---

### Phase 6: Documentation

#### Task 6.1: Update API Documentation

**File:** `apps/opik-backend/docs/api/llm-provider-api-keys.md`

```markdown
# LLM Provider API Keys

## Overview
Manage LLM provider API keys and configurations for workspace.

## Endpoints

### GET /v1/private/llm-provider-keys
List all configured providers for workspace.

**Response:**
```json
{
  "content": [
    {
      "id": "uuid",
      "provider": "openai",
      "providerName": null,
      "apiKey": "[ENCRYPTED]",
      "name": "OpenAI API",
      "baseUrl": null,
      "configuration": {}
    },
    {
      "id": "uuid",
      "provider": "custom-llm",
      "providerName": "ollama",
      "apiKey": "",
      "name": "My Ollama Server",
      "baseUrl": "http://localhost:11434/v1",
      "configuration": {
        "models": ["llama-3.2", "codellama"]
      }
    }
  ]
}
```

### POST /v1/private/llm-provider-keys
Create or update provider configuration.

**Request for Custom Provider:**
```json
{
  "provider": "custom-llm",
  "providerName": "ollama",
  "name": "My Ollama Server",
  "apiKey": "",
  "baseUrl": "http://localhost:11434/v1",
  "configuration": {
    "models": ["llama-3.2", "codellama"]
  }
}
```

**Validation:**
- `providerName` is **required** for `custom-llm` providers
- `providerName` must be unique per workspace
- `baseUrl` is required for custom providers
```

**Estimated Time:** 3 hours

---

#### Task 6.2: Update User Documentation

**File:** `apps/opik-documentation/documentation/fern/docs/prompt_engineering/playground.mdx`

Update to reflect support for multiple custom providers:

```markdown
## Supported LLM Providers

Opik Playground supports the following providers:

### Managed Providers
- **OpenAI**: GPT-4, GPT-3.5, etc.
- **Anthropic**: Claude 3.5 Sonnet, Claude 3 Opus, etc.
- **Google Gemini**: Gemini 1.5 Pro, etc.
- **Vertex AI**: Google Cloud AI models
- **OpenRouter**: Access to multiple models

### Custom Providers
You can add multiple custom LLM providers that are compatible with the OpenAI API:

- **Ollama**: Run models locally
- **vLLM**: High-performance inference server
- **LM Studio**: Desktop app for local models
- **Any OpenAI-compatible API**: Custom deployments

#### Adding a Custom Provider

1. Navigate to **Settings → AI Providers**
2. Click **"Add Custom Provider"**
3. Enter provider details:
   - **Provider Name**: Unique identifier (e.g., "ollama")
   - **Display Name**: Friendly name shown in UI
   - **Base URL**: API endpoint (e.g., "http://localhost:11434/v1")
   - **API Key**: Optional for local providers
   - **Models**: Comma-separated list of available models

4. Click **"Add Provider"**

#### Using Custom Provider Models

In the Playground, custom provider models appear in the model selector with the format:
```
{provider-name}/{model-name}
```

Example: `ollama/llama-3.2`

You can have multiple custom providers configured simultaneously (e.g., both Ollama and vLLM).
```

**Estimated Time:** 2 hours

---

## Technical Specifications

### Database Schema

**Modified Table: `llm_provider_api_key`**

| Column | Type | Description |
|--------|------|-------------|
| `id` | CHAR(36) | Primary key (UUID) |
| `provider` | VARCHAR(250) | Provider type enum |
| **`provider_name`** | **VARCHAR(100)** | **NEW: Unique identifier for custom providers** |
| `workspace_id` | VARCHAR(150) | Workspace identifier |
| `api_key` | TEXT | Encrypted API key |
| `name` | VARCHAR(255) | Display name |
| `base_url` | VARCHAR(255) | Custom provider endpoint |
| `headers` | JSON | Custom headers |
| `configuration` | JSON | Provider-specific config (models, etc.) |
| `created_at` | TIMESTAMP(6) | Creation timestamp |
| `created_by` | VARCHAR(100) | Creator |
| `last_updated_at` | TIMESTAMP(6) | Update timestamp |
| `last_updated_by` | VARCHAR(100) | Updater |

**Constraints:**
```sql
PRIMARY KEY (id)
UNIQUE (workspace_id, provider, provider_name)
```

**Index Strategy:**
- Composite unique index on `(workspace_id, provider, provider_name)`
- For non-custom providers: `provider_name` is NULL
- For custom providers: `provider_name` is required and unique per workspace

---

### Model Naming Convention

**Format:**
```
{provider-name}/{model-name}
```

**Examples:**
- `ollama/llama-3.2`
- `vllm/mistral-7b`
- `lm-studio/codellama`
- `openai/gpt-4` (unchanged for managed providers)

**Parsing Logic:**
```java
// Extract provider name
String providerName = model.substring(0, model.indexOf("/"));

// Extract model name
String modelName = model.substring(model.indexOf("/") + 1);
```

---

### API Contract

**ProviderApiKey DTO:**
```json
{
  "id": "uuid",
  "provider": "custom-llm",
  "providerName": "ollama",
  "apiKey": "[encrypted]",
  "name": "My Ollama Server",
  "baseUrl": "http://localhost:11434/v1",
  "headers": {},
  "configuration": {
    "models": ["llama-3.2", "codellama"]
  },
  "createdAt": "2024-01-01T00:00:00Z",
  "lastUpdatedAt": "2024-01-01T00:00:00Z"
}
```

**Validation Rules:**
- `provider`: Required, must be valid `LlmProvider` enum value
- `providerName`: Required for `CUSTOM_LLM`, null for others
- `providerName`: Must match regex `^[a-z0-9-]+$`
- `baseUrl`: Required for `CUSTOM_LLM`
- `configuration.models`: Required array for `CUSTOM_LLM`

---

## UI/UX Design

### Provider Configuration Page Layout

```
┌────────────────────────────────────────────────────┐
│  AI Providers Configuration                        │
├────────────────────────────────────────────────────┤
│                                                     │
│  Managed Providers                                 │
│  ┌──────────────────────────────────────────────┐ │
│  │ OpenAI                      [Configured] ✓   │ │
│  │ Anthropic                   [Configured] ✓   │ │
│  │ Gemini                      [Not Configured] │ │
│  │ Vertex AI                   [Not Configured] │ │
│  │ OpenRouter                  [Not Configured] │ │
│  └──────────────────────────────────────────────┘ │
│                                                     │
│  Custom LLM Providers            [+ Add Custom]    │
│  ┌──────────────────────────────────────────────┐ │
│  │ My Ollama Server         ollama     [Edit][X]│ │
│  │ http://localhost:11434/v1                    │ │
│  │ 2 models                                     │ │
│  ├──────────────────────────────────────────────┤ │
│  │ vLLM Production          vllm       [Edit][X]│ │
│  │ https://vllm.example.com/v1                  │ │
│  │ 1 model                                      │ │
│  └──────────────────────────────────────────────┘ │
│                                                     │
└────────────────────────────────────────────────────┘
```

### Custom Provider Dialog

**Create Mode:**
```
┌────────────────────────────────────────────────────┐
│  Add Custom Provider                          [X]  │
├────────────────────────────────────────────────────┤
│                                                     │
│  Provider Name *                                   │
│  ┌──────────────────────────────────────────────┐ │
│  │ ollama                                       │ │
│  └──────────────────────────────────────────────┘ │
│  Unique identifier (lowercase, hyphens allowed)    │
│                                                     │
│  Display Name *                                    │
│  ┌──────────────────────────────────────────────┐ │
│  │ My Ollama Server                             │ │
│  └──────────────────────────────────────────────┘ │
│  Friendly name shown in the UI                     │
│                                                     │
│  Base URL *                                        │
│  ┌──────────────────────────────────────────────┐ │
│  │ http://localhost:11434/v1                    │ │
│  └──────────────────────────────────────────────┘ │
│  OpenAI-compatible API endpoint                    │
│                                                     │
│  API Key                                           │
│  ┌──────────────────────────────────────────────┐ │
│  │ ••••••••••••                                 │ │
│  └──────────────────────────────────────────────┘ │
│  Optional for local providers                      │
│                                                     │
│  Models *                                          │
│  ┌──────────────────────────────────────────────┐ │
│  │ llama-3.2, codellama, mistral                │ │
│  └──────────────────────────────────────────────┘ │
│  Comma-separated list of model names               │
│                                                     │
│               [Cancel]  [Add Provider]             │
└────────────────────────────────────────────────────┘
```

**Edit Mode:**
- Provider Name field is **disabled** (immutable)
- All other fields editable
- Dialog title: "Edit Custom Provider"
- Button: "Save Changes"

### Model Selector in Playground

```
┌────────────────────────────────────────────────────┐
│  Select Model                                 [X]  │
├────────────────────────────────────────────────────┤
│  Search models...                                  │
│  ┌──────────────────────────────────────────────┐ │
│  │                                              │ │
│  └──────────────────────────────────────────────┘ │
│                                                     │
│  OpenAI                                            │
│  • gpt-4-turbo                                     │
│  • gpt-4                                           │
│  • gpt-3.5-turbo                                   │
│                                                     │
│  Anthropic                                         │
│  • claude-3-5-sonnet-20241022                      │
│  • claude-3-opus-20240229                          │
│                                                     │
│  My Ollama Server (ollama)                         │
│  • ollama/llama-3.2                                │
│  • ollama/codellama                                │
│                                                     │
│  vLLM Production (vllm)                            │
│  • vllm/mistral-7b                                 │
│                                                     │
└────────────────────────────────────────────────────┘
```

---

## Testing Strategy

### Unit Tests

**Backend:**
- ✅ DAO layer CRUD operations with `provider_name`
- ✅ Service layer validation and business logic
- ✅ Model name parsing utilities
- ✅ Provider resolution logic

**Frontend:**
- ✅ Form validation (Zod schemas)
- ✅ Model ID parsing helpers
- ✅ Component rendering
- ✅ User interactions

### Integration Tests

**Backend:**
- ✅ Full flow: Save → Retrieve → Update → Delete custom providers
- ✅ Provider resolution from model name
- ✅ Chat model creation with custom provider config
- ✅ Error handling for missing/invalid providers

**Frontend:**
- ✅ Provider management flow
- ✅ Model selection in playground
- ✅ API integration with backend

### E2E Tests

**User Flows:**
- ✅ Add first custom provider
- ✅ Add multiple custom providers
- ✅ Edit custom provider
- ✅ Delete custom provider
- ✅ Use custom provider model in playground
- ✅ Use custom provider model in online scoring
- ✅ Error handling for duplicate provider names

---

## Migration & Backward Compatibility

### Database Migration

**Migration Steps:**
1. Add `provider_name` column (nullable)
2. Drop old unique constraint
3. Add new compound unique constraint
4. Migrate existing custom providers to have `provider_name = 'default'`

**Rollback Plan:**
If migration fails:
1. Revert new constraint
2. Restore old constraint
3. Drop `provider_name` column

### API Backward Compatibility

**Old API Behavior (Pre-Migration):**
- `GET /llm-provider-keys` returns `providerName: null` for all providers
- Frontend ignores `providerName` field

**New API Behavior (Post-Migration):**
- `GET /llm-provider-keys` returns `providerName` for custom providers
- Frontend uses `providerName` to build model IDs

**Compatibility Strategy:**
- Old clients (without `providerName` support) continue to work for non-custom providers
- Old clients can't add new custom providers (validation enforces `providerName`)
- Existing custom providers get `providerName = 'default'` and continue to work

### Frontend Migration

**Version 1.x (Current):**
- Single custom provider slot
- Model format: `custom-llm/model-name`

**Version 2.x (New):**
- Multiple custom provider slots
- Model format: `{provider-name}/model-name`
- Existing custom provider accessible as `default/model-name`

**Migration UX:**
- Show migration banner: "Your custom provider is now named 'default'. You can rename it or add additional providers."
- One-time prompt to rename default provider

---

## Estimation Summary

| Phase | Tasks | Estimated Time |
|-------|-------|----------------|
| **Phase 1: Database** | 2 tasks | **5 hours** |
| **Phase 2: Backend** | 4 tasks | **13 hours** |
| **Phase 3: Frontend** | 5 tasks | **15 hours** |
| **Phase 4: API** | 1 task | **3 hours** |
| **Phase 5: Testing** | 4 tasks | **24 hours** |
| **Phase 6: Documentation** | 2 tasks | **5 hours** |
| **Total** | **18 tasks** | **65 hours** |

**Note:** Estimates include buffer for code review, testing, and iteration.

---

## Appendix

### Key Files Modified

**Backend:**
1. `000XXX_add_provider_name_column.sql` (NEW)
2. `LlmProviderApiKeyDAO.java`
3. `LlmProviderApiKeyService.java`
4. `ProviderApiKey.java`
5. `CustomLlmModelNameChecker.java`
6. `LlmProviderFactoryImpl.java`
7. `LlmProviderApiKeyResource.java`

**Frontend:**
8. `providers.ts`
9. `schema.ts`
10. `CustomProvidersSection.tsx` (NEW)
11. `CustomProviderDialog.tsx` (NEW)
12. `useCustomProviderModels.ts`

### Dependencies

**Backend:**
- No new dependencies required

**Frontend:**
- No new dependencies required

### Risks & Mitigation

| Risk | Impact | Mitigation |
|------|--------|------------|
| Database migration fails | High | Test migration in staging, prepare rollback script |
| Breaking change for existing custom providers | Medium | Implement backward-compatible migration |
| Model name conflicts across providers | Low | Validate uniqueness in frontend, clear error messages |
| Performance impact with many custom providers | Low | Implement pagination in UI, add database indexes |

---

## Conclusion

This implementation plan provides a comprehensive solution for supporting multiple custom LLM providers in Opik. The design maintains backward compatibility while enabling powerful new capabilities for users who want to configure multiple Ollama, vLLM, or other OpenAI-compatible providers.

**Key Benefits:**
- ✅ Unlimited custom providers per workspace
- ✅ Clear separation between managed and custom providers
- ✅ Intuitive UI for managing multiple configurations
- ✅ Backward compatible with existing deployments
- ✅ Extensible architecture for future provider types

**Next Steps:**
1. Review and approve this implementation plan
2. Create Jira tickets for each phase/task
3. Assign tasks to development team
4. Begin with Phase 1 (Database Migration)
5. Proceed sequentially through phases

---

**Document Version:** 1.0  
**Last Updated:** 2025-01-30  
**Author:** AI Assistant  
**Status:** Draft - Pending Review
