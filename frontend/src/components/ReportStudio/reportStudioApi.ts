import {
  ApplyPlaceholderResponse,
  DocumentContentControl,
  GeneratePlaceholderResponse,
  PlaceholderConfig,
} from './types';

/** Report Studio placeholders are stored against this single editable document. */
export const DOCUMENT_ID = 'default';

const JSON_HEADERS = { 'Content-Type': 'application/json', Accept: 'application/json' };

/** Content controls scanned from the saved Word document, with the document version they were read from. */
export interface ContentControlScan {
  documentVersion: number;
  controls: DocumentContentControl[];
}

/** Reporting period detected from the sentences surrounding a content control. */
export interface DurationDetection {
  duration?: string;
  sentences?: string[];
  analyzed?: boolean;
  message?: string;
}

/**
 * Scans the saved ONLYOFFICE document for content controls.
 *
 * @throws Error when the backend scan fails.
 */
export async function fetchContentControls(): Promise<ContentControlScan> {
  const response = await fetch('/api/report-studio/onlyoffice/content-controls', {
    headers: { Accept: 'application/json' },
  });
  if (!response.ok) {
    throw new Error(`Placeholder scan failed (${response.status})`);
  }
  const payload = (await response.json()) as ContentControlScan;
  return { ...payload, controls: Array.isArray(payload.controls) ? payload.controls : [] };
}

/**
 * Registers the tagged controls with the backend and returns their persisted configurations keyed by tag.
 * Returns `undefined` when the backend rejects the request, so callers can keep their current configurations.
 */
export async function syncPlaceholderConfigs(
  controls: DocumentContentControl[],
): Promise<Record<string, PlaceholderConfig> | undefined> {
  const placeholders = controls.filter((c) => c.tag).map((c) => ({ tag: c.tag, alias: c.alias }));
  const response = await fetch('/api/report-studio/placeholders/batch', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ documentId: DOCUMENT_ID, placeholders }),
  });
  if (!response.ok) return undefined;
  const configs = (await response.json()) as PlaceholderConfig[];
  const byTag: Record<string, PlaceholderConfig> = {};
  configs.forEach((config) => {
    if (config.tag) byTag[config.tag] = config;
  });
  return byTag;
}

/**
 * Asks the backend to detect the reporting period stated near a content control.
 *
 * @throws Error with the backend detail when detection fails, or an AbortError when `signal` is aborted.
 */
export async function detectDuration(control: DocumentContentControl, signal: AbortSignal): Promise<DurationDetection> {
  const response = await fetch('/api/report-studio/onlyoffice/content-controls/duration', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ tag: control.tag, wordId: control.wordId, alias: control.alias }),
    signal,
  });
  const payload = (await response.json()) as DurationDetection & { detail?: string };
  if (!response.ok) {
    throw new Error(payload.detail ?? `Duration detection failed (${response.status})`);
  }
  return payload;
}

/**
 * Generates an execution blueprint for a placeholder prompt and runs its query.
 * A query failure is returned in `error`; only a failed request throws.
 *
 * @throws Error when the request itself fails.
 */
export async function generatePlaceholder(
  tag: string,
  alias: string,
  prompt: string,
): Promise<GeneratePlaceholderResponse> {
  const response = await fetch('/api/report-studio/placeholders/generate', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ documentId: DOCUMENT_ID, tag, alias, prompt }),
  });
  const payload = (await response.json()) as GeneratePlaceholderResponse & { detail?: string };
  if (!response.ok) {
    throw new Error(payload.detail ?? payload.error ?? `Generate failed (${response.status})`);
  }
  return payload;
}

/**
 * Writes the result of a run into every content control with the given alias in the source document.
 *
 * @throws Error when the backend cannot save the result.
 */
export async function applyPlaceholder(runId: string, alias: string): Promise<ApplyPlaceholderResponse> {
  const response = await fetch('/api/report-studio/placeholders/apply', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ runId, alias }),
  });
  const payload = (await response.json()) as ApplyPlaceholderResponse & { detail?: string };
  if (!response.ok) {
    throw new Error(payload.detail ?? `Save failed (${response.status})`);
  }
  return payload;
}
