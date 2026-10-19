import { getPreconfiguredPrompt } from './defaultPrompts';
import { DOCUMENT_ID } from './reportStudioApi';
import { firstResultValue, formatResultValue, shortTime } from './resultFormatting';
import { DocumentContentControl, GeneratePlaceholderResponse, PlaceholderConfig, PlaceholderToken } from './types';

/** Reporting period used when neither the URL, the user, the saved configuration nor the prompt provides one. */
export const DEFAULT_DURATION = '2026-01-01 to 2026-01-31';

/** Removes a leading `Duration:` line, leaving the user-written part of a packaged prompt. */
export const userPromptWithoutDuration = (prompt: string) => prompt.replace(/^Duration:[^\r\n]*(?:\r?\n)?/i, '');

/** Reads the reporting period from a leading `Duration:` line, if the prompt has one. */
export const durationFromPrompt = (prompt: string) => {
  const match = prompt.match(/^Duration:\s*([^\r\n]*)/i);
  return match?.[1]?.trim() || undefined;
};

/** Combines the reporting period and the user's instructions into the prompt sent to the backend. */
export const packagePrompt = (duration: string, userPrompt: string) =>
  'Duration: ' + duration + (userPrompt.trim() ? '\n' + userPrompt : '');

/** Returns the preconfigured prompt for a control, looked up by tag, then alias, then ID. */
export function preconfiguredPromptFor(control: DocumentContentControl): string {
  return getPreconfiguredPrompt(control.tag || control.alias || control.id)?.prompt || '';
}

/**
 * Chooses the prompt shown when a control is selected: the saved prompt, then the token's prompt, then the
 * preconfigured default. Blank prompts are skipped.
 */
export function initialPromptFor(
  control: DocumentContentControl,
  persisted: PlaceholderConfig | undefined,
  token: PlaceholderToken | undefined,
): string {
  if (persisted?.prompt && persisted.prompt.trim().length > 0) return persisted.prompt;
  if (token?.prompt && token.prompt.trim().length > 0) return token.prompt;
  return preconfiguredPromptFor(control);
}

function statusFromConfig(config: PlaceholderConfig): PlaceholderToken['status'] {
  const runStatus = config.lastRun?.status;
  if (runStatus === 'SUCCEEDED' || runStatus === 'APPLIED') return 'executed';
  if (runStatus === 'FAILED') return 'error';
  return config.executionPlan ? 'analyzed' : 'draft';
}

function resolvedValueFromConfig(config: PlaceholderConfig): string | undefined {
  const firstValue = firstResultValue(config.lastRun?.result);
  const resolvedValue = firstValue != null ? String(firstValue) : undefined;
  if (resolvedValue && config.format === 'percentage' && !resolvedValue.endsWith('%')) {
    return resolvedValue + '%';
  }
  return resolvedValue;
}

/**
 * Builds the token displayed for a document content control.
 *
 * A saved configuration takes priority. Without one, the token already held in the in-memory document is reused,
 * and a new draft token is created when neither exists. The prompt falls back to the preconfigured default.
 */
export function buildPlaceholderToken(
  control: DocumentContentControl,
  persisted: PlaceholderConfig | undefined,
  existingToken: PlaceholderToken | undefined,
): PlaceholderToken {
  const label = control.alias || control.tag;
  const defaultPrompt = preconfiguredPromptFor(control);

  if (persisted) {
    return {
      id: control.id,
      label,
      prompt: persisted.prompt || defaultPrompt,
      status: statusFromConfig(persisted),
      semanticPlan: persisted.executionPlan,
      resolvedValue: resolvedValueFromConfig(persisted) ?? existingToken?.resolvedValue,
      unit: persisted.format === 'percentage' ? '%' : undefined,
      updatedAt: persisted.updatedAt ? shortTime(new Date(persisted.updatedAt)) : undefined,
    };
  }
  if (existingToken) {
    return { ...existingToken, id: control.id, label, prompt: existingToken.prompt || defaultPrompt };
  }
  return { id: control.id, label, prompt: defaultPrompt, status: 'draft' };
}

/** A generation request together with the backend response, used to update local state without reloading. */
export interface Generation {
  tag: string;
  alias: string;
  prompt: string;
  duration: string;
  response: GeneratePlaceholderResponse;
}

/** Builds the configuration the backend has just saved for a generation, including its latest run. */
export function configFromGeneration({ tag, alias, prompt, duration, response }: Generation): PlaceholderConfig {
  const now = new Date().toISOString();
  return {
    id: response.configId,
    documentId: DOCUMENT_ID,
    tag,
    alias,
    prompt,
    duration,
    executionPlan: response.blueprint,
    revision: response.revision,
    createdAt: now,
    updatedAt: now,
    lastRun: {
      id: response.runId,
      configRevision: response.revision,
      promptSnapshot: prompt,
      planSnapshot: response.blueprint,
      executedSql: response.result?.sql || '',
      result: response.result,
      status: response.error ? 'FAILED' : 'SUCCEEDED',
      errorMessage: response.error,
      executedAt: now,
    },
  };
}

/** Builds the in-memory document token for a generation, resolved to the formatted first result value. */
export function tokenFromGeneration(control: DocumentContentControl, generation: Generation): PlaceholderToken {
  const { blueprint, result, error } = generation.response;
  const firstValue = firstResultValue(result);
  return {
    id: control.id,
    label: control.alias,
    prompt: generation.prompt,
    status: error ? 'error' : 'executed',
    semanticPlan: blueprint,
    resolvedValue: firstValue != null ? formatResultValue(firstValue, blueprint.format) : '',
    unit: blueprint.format === 'percentage' ? '%' : undefined,
    updatedAt: shortTime(new Date()),
  };
}
