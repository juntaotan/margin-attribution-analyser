/** AI connection settings as stored by the backend. */
export type AiSettings = {
  host: string;
  port: number;
  model: string;
  baseUrl: string;
  updatedAt: string | null;
  persisted: boolean;
};

/** Connection values entered by the user. */
export type AiSettingsRequest = Pick<AiSettings, 'host' | 'port' | 'model'>;

/** Result of probing a llama.cpp server without saving its settings. */
export type ConnectionTest = {
  reachable: boolean;
  modelReady: boolean;
  latencyMs: number;
  message: string;
};

/** Whether an expected placeholder was found in the master template. */
export type PlaceholderItem = {
  key: string;
  label: string;
  detected: boolean;
  samplePreview: string;
};

/** The management-report master template and the core placeholders detected in it. */
export type TemplateMetadata = {
  filename: string;
  sizeBytes: number;
  updatedAt: string;
  isCustom: boolean;
  detectedPlaceholders: PlaceholderItem[];
  matchedCount: number;
  totalExpected: number;
  version: number;
};

const SETTINGS_ENDPOINT = '/api/v1/settings/ai';
const TEMPLATE_ENDPOINT = '/api/v1/settings/template';

/** Browser URL that downloads the current master template. */
export const TEMPLATE_DOWNLOAD_URL = TEMPLATE_ENDPOINT + '/download';

const readError = async (response: Response) => {
  try {
    const body = (await response.json()) as { message?: string };
    return body.message || 'Request failed (HTTP ' + response.status + ')';
  } catch {
    return 'Request failed (HTTP ' + response.status + ')';
  }
};

/**
 * Sends a request and parses its JSON body.
 *
 * @throws Error with the backend's `message` when the response is not successful.
 */
async function requestJson<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init);
  if (!response.ok) throw new Error(await readError(response));
  return (await response.json()) as T;
}

const jsonBody = (method: string, body: unknown): RequestInit => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
});

/** Loads the current llama.cpp connection. */
export const fetchAiSettings = () => requestJson<AiSettings>(SETTINGS_ENDPOINT);

/** Tests a connection without saving it. */
export const testAiConnection = (settings: AiSettingsRequest) =>
  requestJson<ConnectionTest>(SETTINGS_ENDPOINT + '/test', jsonBody('POST', settings));

/** Saves a connection and makes the AI services use it. */
export const saveAiSettings = (settings: AiSettingsRequest) =>
  requestJson<AiSettings>(SETTINGS_ENDPOINT, jsonBody('PUT', settings));

/** Loads the master template's metadata and placeholder readiness. */
export const fetchTemplate = () => requestJson<TemplateMetadata>(TEMPLATE_ENDPOINT);

/** Uploads a Word file as the new master template and returns its metadata. */
export const uploadTemplate = (file: File) => {
  const formData = new FormData();
  formData.append('file', file);
  return requestJson<TemplateMetadata>(TEMPLATE_ENDPOINT + '/upload', { method: 'POST', body: formData });
};

/** Restores the built-in master template and returns its metadata. */
export const resetTemplate = () => requestJson<TemplateMetadata>(TEMPLATE_ENDPOINT + '/reset', { method: 'POST' });
