// Copyright 2025 promptLM
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import type {
  ApiExecutePromptRequest,
  ApiPromptSpec,
  ApiPromptSpecCreationRequest,
  ApiProjectSpec,
  ApiRepositoryOwner,
  CloneProjectPayload,
  ConnectProjectPayload,
  CreateProjectPayload,
  DashboardSummary,
  PromptRequest,
  PromptExecution,
  PromptExecutionPlaceholder,
  PromptEvaluationDefinition,
  PromptEvaluationExtension,
  PromptEvaluationResult,
  PromptEvaluationSpec,
  PromptId,
  PromptSpecDetails,
  PromptSpecDraft,
  PromptSpecSummary,
  ProjectSummary,
  RepositoryOwner,
} from './types';
import type { PromptLMClient, PromptLMClientFactory, PromptLMClientFactoryOptions } from './client';
import { configureGeneratedApiClient } from './generatedClientProvider';
import { toDisplayError } from './apiError';

type RestClientOptions = Required<PromptLMClientFactoryOptions>;

type PromptSpecResponse = ApiPromptSpec;

type PromptSpecListResponseItem = ApiPromptSpec;

type PromptSpecListResponse = PromptSpecListResponseItem[];

type ProjectSpecResponse = ApiProjectSpec;
type RepositoryOwnerListResponse = ApiRepositoryOwner[];

type CreateStoreResponse = ProjectSpecResponse;

type CloneStoreResponse = string;

type PromptCreateProjectRequestParams = CreateProjectPayload;

type PromptSpecDetailsResponse = PromptSpecResponse;

type PromptSpecDetailsRequest = PromptId;

type PromptSpecListResult = PromptSpecSummary[];

type PromptSpecDetailResult = PromptSpecDetails;

type PromptSpecCreateResult = PromptSpecDetails;

type PromptSpecUpdateResult = PromptSpecDetails;

type PromptSpecReleaseResult = PromptSpecDetails;

type PromptSpecExecutionResult = PromptSpecDetails;

type ProjectListResult = ProjectSummary[];

type ProjectCreateResult = ProjectSummary;

type ProjectConnectResult = ProjectSummary;

type ProjectCloneResult = ProjectSummary;

type ProjectActiveResult = ProjectSummary | null;

type PromptSpecRequestPayload = ApiPromptSpecCreationRequest;

type PromptExecuteRequest = ApiExecutePromptRequest;


type RestPromptLMClient = PromptLMClient;

type RestPromptLMClientFactory = PromptLMClientFactory;

type RestClientFactoryOptions = PromptLMClientFactoryOptions;

const asRecord = (value: unknown): Record<string, unknown> =>
  value && typeof value === 'object' ? (value as Record<string, unknown>) : {};

type PromptListRequestParams = {
  includeRetired?: boolean;
  group?: string;
  nameContains?: string;
  sortBy?: string;
  sortDirection?: 'asc' | 'desc';
};

const DEFAULT_BASE_URL = '';

const toPromptSpecSummary = (item: PromptSpecListResponseItem): PromptSpecSummary => ({
  id: item.id ?? '',
  name: item.name ?? '',
  projectId: (item as { projectId?: string }).projectId ?? 'default-project',
  group: item.group ?? undefined,
  version: item.version ?? undefined,
  revision: item.revision ?? undefined,
  status: item.status ?? 'ACTIVE',
  description: item.description ?? undefined,
  updatedAt: item.updatedAt ?? undefined,
});

const toPromptSpecDetails = (response: PromptSpecDetailsResponse): PromptSpecDetails => {
  const normalizedPlaceholders = {
    startPattern: (response.placeholders as { startPattern?: string })?.startPattern ?? '{{',
    endPattern: (response.placeholders as { endPattern?: string })?.endPattern ?? '}}',
    list: Array.isArray(response.placeholders?.list)
      ? response.placeholders!.list.map((placeholder, index) => ({
          name: placeholder.name ?? `placeholder-${index}`,
          value: (placeholder as { value?: string; defaultValue?: string }).value ?? placeholder.defaultValue ?? '',
        }))
      : [],
  };

  const normalizedRequest = (() => {
    if (!response.request) {
      return {
        type: 'chat/completion' as const,
        vendor: '',
        model: '',
        url: undefined,
        modelSnapshot: undefined,
        parameters: {},
        messages: [],
      };
    }

    const requestMessages = (response.request as { messages?: unknown }).messages;

    const messages = Array.isArray(requestMessages)
      ? requestMessages.map((message, index) => ({
          id: (message as { id?: string }).id ?? `remote-${index}-${Math.random().toString(16).slice(2, 8)}`,
          role:
            typeof message.role === 'string' && ['system', 'user', 'assistant', 'tool'].includes(message.role.toLowerCase())
              ? (message.role.toLowerCase() as PromptSpecDraft['request']['messages'][number]['role'])
              : 'user',
          name: message.name ?? undefined,
          content: message.content ?? '',
        }))
      : [];

    const requestType = (response.request as { type?: string }).type;
    const type: PromptRequest['type'] =
      requestType === 'chat/completion' ? 'chat/completion' : 'chat/completion';

    return {
      type,
      vendor: response.request.vendor ?? '',
      model: response.request.model ?? '',
      url: response.request.url ?? undefined,
      modelSnapshot:
        (response.request as { modelSnapshot?: string; model_snapshot?: string }).modelSnapshot ??
        (response.request as { model_snapshot?: string }).model_snapshot,
      parameters: (() => {
        const params = asRecord((response.request as { parameters?: unknown }).parameters);
        return {
          temperature: params.temperature as number | undefined,
          topP: params.topP as number | undefined,
          maxTokens: params.maxTokens as number | undefined,
          frequencyPenalty: params.frequencyPenalty as number | undefined,
          presencePenalty: params.presencePenalty as number | undefined,
          stream: params.stream as boolean | undefined,
        };
      })(),
      messages,
    };
  })();

  const normalizedExecutions: PromptExecution[] = Array.isArray(response.executions)
    ? response.executions.map((execution, index) => {
        const placeholders = Array.isArray(execution.placeholders)
          ? execution.placeholders.map((ph, index: number) => {
              const placeholder = ph as { name?: string; defaultValue?: string };
              return {
                name: placeholder.name ?? `placeholder-${index}`,
                defaultValue: placeholder.defaultValue,
              };
            })
          : [];

        const evaluations = Array.isArray(execution.evaluations)
          ? execution.evaluations.map((ev, idx: number) => {
              const evaluation = ev as {
                evaluator?: string;
                type?: string;
                score?: number;
                reasoning?: string;
                comments?: string;
                success?: boolean;
              };
              return {
                evaluator: evaluation.evaluator ?? '',
                type: evaluation.type ?? '',
                score: evaluation.score,
                reasoning: evaluation.reasoning,
                comments: evaluation.comments,
                success: evaluation.success ?? false,
              };
            })
          : [];

        return {
          id: execution.id ?? `exec-${index}-${Date.now()}`,
          timestamp: execution.timestamp ?? new Date().toISOString(),
          response: execution.response
            ? {
                type: 'chat/completion',
                content: execution.response.content,
              }
            : undefined,
          placeholders,
          evaluations,
        };
      })
    : [];

  const spec: PromptSpecDetails = {
    ...response,
    name: (() => {
      if (!response.name) {
        throw new Error('PromptSpecDetails missing required name');
      }
      return response.name;
    })(),
    id: (() => {
      const resolvedId = response.id ?? (response as { promptId?: string | null }).promptId;
      if (!resolvedId) {
        throw new Error('PromptSpecDetails missing required id');
      }
      return resolvedId;
    })(),
    projectId: (() => {
      return (response as { projectId?: string | null }).projectId ?? 'default-project';
    })(),
    authors: Array.isArray(response.authors) ? response.authors : [],
    placeholders: normalizedPlaceholders,
    request: normalizedRequest,
    executions: normalizedExecutions,
    status: (response.status as PromptStatus | undefined) ?? 'ACTIVE',
    response: response.response
      ? {
          type: 'chat/completion',
          durationMs: (response.response as { durationMs?: number }).durationMs,
          usage: (response.response as { usage?: unknown }).usage,
          content: (response.response as { content?: string }).content,
        }
      : undefined,
  };

  return spec;
};

const toProjectSummary = (project: ProjectSpecResponse): ProjectSummary => ({
  id: project.id ?? '',
  name: project.name ?? project.localPath ?? project.repositoryUrl ?? project.id ?? '',
  localPath: project.localPath ?? '',
  repositoryUrl: project.repositoryUrl ?? '',
  description: project.description,
  promptCount:
    typeof (project as { promptCount?: unknown }).promptCount === 'number'
      ? ((project as { promptCount?: number }).promptCount ?? 0)
      : 0,
  createdAt: project.createdAt ?? new Date().toISOString(),
  updatedAt: project.updatedAt ?? new Date().toISOString(),
  healthStatus: (project as { healthStatus?: unknown }).healthStatus as ProjectSummary['healthStatus'] ?? null,
  healthMessage: (project as { healthMessage?: unknown }).healthMessage as ProjectSummary['healthMessage'] ?? null,
});

const formatHttpError = (err: unknown): Error => toDisplayError(err);

const buildRequestPayload = (draft: PromptSpecDraft) => {
  const parametersMap: Record<string, number | boolean | undefined> = {};
  if (draft.request.parameters.temperature !== undefined) {
    parametersMap.temperature = draft.request.parameters.temperature;
  }
  if (draft.request.parameters.topP !== undefined) {
    parametersMap.topP = draft.request.parameters.topP;
  }
  if (draft.request.parameters.maxTokens !== undefined) {
    parametersMap.maxTokens = draft.request.parameters.maxTokens;
  }
  if (draft.request.parameters.frequencyPenalty !== undefined) {
    parametersMap.frequencyPenalty = draft.request.parameters.frequencyPenalty;
  }
  if (draft.request.parameters.presencePenalty !== undefined) {
    parametersMap.presencePenalty = draft.request.parameters.presencePenalty;
  }
  if (draft.request.parameters.stream !== undefined) {
    parametersMap.stream = draft.request.parameters.stream;
  }

  return {
    type: draft.request.type,
    vendor: draft.request.vendor,
    model: draft.request.model,
    url: draft.request.url,
    model_snapshot: draft.request.modelSnapshot,
    parameters: parametersMap,
    messages: draft.request.messages.map((message) => ({
      role: message.role.toUpperCase(),
      content: message.content,
      name: message.name,
    })),
  };
};

const readEvaluationExtension = (draft: PromptSpecDraft): PromptEvaluationExtension | undefined => {
  const extensions = draft.extensions ?? {};
  const evaluation = extensions['x-evaluation'];
  if (!evaluation || typeof evaluation !== 'object') {
    return undefined;
  }
  return evaluation as PromptEvaluationExtension;
};

const normalizeEvaluationSpec = (draft: PromptSpecDraft): PromptEvaluationSpec | undefined => {
  const evaluations = readEvaluationExtension(draft)?.spec?.evaluations ?? [];
  if (!evaluations.length) {
    return undefined;
  }

  const normalizedEvaluations = evaluations
    .map((evaluation) => {
      const evaluator = evaluation.evaluator?.trim();
      const type = evaluation.type?.trim();
      if (!evaluator || !type) {
        return null;
      }

      const description = evaluation.description?.trim();
      return {
        evaluator,
        type,
        description: description && description.length > 0 ? description : undefined,
      };
    })
    .filter((evaluation): evaluation is PromptEvaluationDefinition => evaluation !== null);

  if (!normalizedEvaluations.length) {
    return undefined;
  }

  return {
    evaluations: normalizedEvaluations,
  };
};

const normalizeExtensions = (draft: PromptSpecDraft): Record<string, unknown> | undefined => {
  const base = draft.extensions ? { ...draft.extensions } : {};
  const normalizedSpec = normalizeEvaluationSpec(draft);
  const rawEvaluation = base['x-evaluation'];
  const evaluation =
    rawEvaluation && typeof rawEvaluation === 'object' && rawEvaluation !== null
      ? { ...(rawEvaluation as Record<string, unknown>) }
      : {};

  if (normalizedSpec) {
    evaluation.spec = normalizedSpec;
  } else {
    delete evaluation.spec;
  }

  if (Object.keys(evaluation).length > 0) {
    base['x-evaluation'] = evaluation;
  } else {
    delete base['x-evaluation'];
  }

  return Object.keys(base).length > 0 ? base : undefined;
};

const mapPromptSpecDraftToRequest = (draft: PromptSpecDraft): PromptSpecRequestPayload => {
  const placeholderEntries = draft.placeholders.list.map((item) => [item.name, item.value ?? '']);
  const userMessage = draft.request.messages.find((message) => message.role === 'user')?.content ?? '';
  const systemMessage = draft.request.messages.find((message) => message.role === 'system')?.content ?? '';
  const extensions = normalizeExtensions(draft);

  const payload: PromptSpecRequestPayload = {
    id: draft.metadata.id,
    name: draft.metadata.name,
    group: draft.metadata.group,
    description: draft.metadata.description,
    placeholder: Object.fromEntries(placeholderEntries),
    userMessage,
    type: draft.request.type,
    vendorAndModel: {
      vendorName: draft.request.vendor,
      model: draft.request.model,
    },
    request: buildRequestPayload(draft),
    repositoryUrl: draft.metadata.repositoryUrl,
    version: draft.metadata.version,
    extensions,
    messages: [
      {
        role: 'SYSTEM',
        content: systemMessage,
      },
      {
        role: 'USER',
        content: userMessage,
      },
    ],
  };

  if (payload.placeholder && Object.keys(payload.placeholder).length === 0) {
    delete payload.placeholder;
  }

  return payload;
};

const mapPromptSpecDraftToSpec = (draft: PromptSpecDraft): ApiPromptSpec => {
  const placeholders = draft.placeholders.list.map((item) => ({
    name: item.name,
    value: item.value ?? '',
  }));

  const spec: ApiPromptSpec = {
    id: draft.metadata.id,
    name: draft.metadata.name,
    projectId: draft.metadata.projectId,
    group: draft.metadata.group,
    version: draft.metadata.version,
    revision: draft.metadata.revision,
    description: draft.metadata.description,
    authors: draft.metadata.authors,
    repositoryUrl: draft.metadata.repositoryUrl,
    request: buildRequestPayload(draft) as ApiPromptSpec['request'],
    placeholders: {
      startPattern: draft.placeholders.startPattern,
      endPattern: draft.placeholders.endPattern,
      list: placeholders,
    },
    extensions: draft.extensions,
  };

  return spec;
};

const mapProjectResponseToSummary = (response: ProjectSpecResponse): ProjectSummary => {
  const base = toProjectSummary(response);
  return {
    ...base,
    promptCount: typeof response.promptCount === 'number' ? response.promptCount : base.promptCount,
  };
};

const createPromptLMClient = ({ baseUrl }: RestClientOptions): RestPromptLMClient => {
  const { promptSpecs, promptStore } = configureGeneratedApiClient({ baseUrl });

  const withError = async <T>(action: () => Promise<T>): Promise<T> => {
    try {
      return await action();
    } catch (err) {
      throw formatHttpError(err);
    }
  };

  const listPrompts = async (_params: PromptListRequestParams = {}): Promise<PromptSpecListResult> =>
    withError(async () => {
      const data = await promptSpecs.listPromptSpecs();
      return (data ?? []).map(toPromptSpecSummary);
    });

  const getPromptDetails = async (id: PromptSpecDetailsRequest): Promise<PromptSpecDetailResult> =>
    withError(async () => {
      const data = await promptSpecs.getById(id);
      return toPromptSpecDetails(data);
    });

  const createPrompt = async (draft: PromptSpecDraft): Promise<PromptSpecCreateResult> =>
    withError(async () => {
      const payload = mapPromptSpecDraftToRequest(draft);
      const data = await promptSpecs.createPromptSpec(payload);
      return toPromptSpecDetails(data);
    });

  const updatePrompt = async (id: PromptId, draft: PromptSpecDraft): Promise<PromptSpecUpdateResult> =>
    withError(async () => {
      const payload = mapPromptSpecDraftToRequest(draft);
      const data = await promptSpecs.updatePromptSpec(id, payload);
      return toPromptSpecDetails(data);
    });

  const releasePrompt = async (id: PromptId): Promise<PromptSpecReleaseResult> =>
    withError(async () => {
      const data = await promptSpecs.releasePrompt(id);
      return toPromptSpecDetails(data);
    });

  const executePrompt = async (draft: PromptSpecDraft): Promise<PromptSpecExecutionResult> =>
    withError(async () => {
      const payload: PromptExecuteRequest = {
        promptSpec: mapPromptSpecDraftToSpec(draft),
      } as PromptExecuteRequest;
      const data = await promptSpecs.executePrompt(payload);
      return toPromptSpecDetails(data);
    });

  const executeStoredPrompt = async (id: PromptId, draft: PromptSpecDraft): Promise<PromptSpecExecutionResult> =>
    withError(async () => {
      const payload: PromptExecuteRequest = {
        promptSpec: mapPromptSpecDraftToSpec(draft),
      } as PromptExecuteRequest;
      const data = await promptSpecs.executeStoredPrompt(id, payload);
      return toPromptSpecDetails(data);
    });

  const listProjects = async (): Promise<ProjectListResult> =>
    withError(async () => {
      const data = await promptStore.getAllProjects();
      return (data ?? []).map(mapProjectResponseToSummary);
    });

  const getActiveProject = async (): Promise<ProjectActiveResult> => {
    try {
      const data = await promptStore.getActiveProject();
      if (!data) {
        return null;
      }
      return mapProjectResponseToSummary(data);
    } catch (err) {
      const status = (err as { status?: number }).status;
      if (status === 404) {
        return null;
      }
      throw formatHttpError(err);
    }
  };

  const listRepositoryOwners = async (): Promise<RepositoryOwner[]> =>
    withError(async () => {
      const data = await promptStore.listOwners();
      return (data ?? []).map((owner, index) => ({
        id: owner.id ?? owner.displayName ?? `owner-${index}`,
        displayName: owner.displayName ?? owner.id ?? `Owner ${index + 1}`,
        type: owner.type ?? 'USER',
      }));
    });

  const createProject = async (payload: PromptCreateProjectRequestParams): Promise<ProjectCreateResult> =>
    withError(async () => {
      const data = await promptStore.createStore(payload);
      return mapProjectResponseToSummary(data);
    });

  const connectProject = async (payload: PromptConnectProjectRequestParams): Promise<ProjectConnectResult> =>
    withError(async () => {
      const data = await promptStore.connectRepository({
        repoPath: payload.repoPath as unknown as Record<string, unknown>,
        displayName: payload.displayName,
      });
      return mapProjectResponseToSummary(data);
    });

  const cloneProject = async (payload: PromptCloneProjectRequestParams): Promise<ProjectCloneResult> =>
    withError(async () => {
      const data = await promptStore.cloneStore(payload);
      return {
        id: data,
        name: payload.name ?? payload.remoteUrl.split('/').pop() ?? data,
        localPath: `${payload.targetDir}/${payload.name ?? data}`,
        repositoryUrl: payload.remoteUrl,
        promptCount: 0,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
    });

  const switchProject = async (projectId: ProjectId): Promise<void> =>
    withError(async () => {
      await promptStore.switchProject(projectId);
    });

  const getDashboardSummary = async (): Promise<DashboardSummaryResult> =>
    withError(async () => {
      const data = await promptSpecs.getPromptStats();
      return {
        totalPrompts: data.totalPrompts ?? 0,
        activeProjects: (data as { activeProjects?: number }).activeProjects ?? 0,
        lastUpdated: (data as { lastUpdated?: string }).lastUpdated ?? new Date().toISOString(),
      };
    });

  return {
    getDashboardSummary,
    listPrompts,
    getPromptDetails,
    createPrompt,
    updatePrompt,
    executePrompt,
    executeStoredPrompt,
    listProjects,
    getActiveProject,
    listRepositoryOwners,
    releasePrompt,
    createProject,
    connectProject,
    cloneProject,
    switchProject,
  };
};

export const createRestClient: RestPromptLMClientFactory = (options: RestClientFactoryOptions = {}) => {
  const config: RestClientOptions = {
    baseUrl: options.baseUrl ?? DEFAULT_BASE_URL,
  };

  return createPromptLMClient(config);
};
