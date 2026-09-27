export type ImportStatus =
  | 'PENDING'
  | 'VALIDATING'
  | 'VALIDATED'
  | 'VALIDATING_FAILED'
  | 'STORING'
  | 'STORED'
  | 'STORING_FAILED'
  | 'TRANSFORMING'
  | 'TRANSFORMED'
  | 'TRANSFORMING_FAILED'
  | 'WRITING_TO_DATALAKE'
  | 'WRITE_SUCCESS'
  | 'WRITE_FAILED'
  | 'CANCELLED';

export type ImportJob = {
  jobId: number;
  filename: string;
  targetTable: string;
  status: ImportStatus;
  stage: string;
  progress: number;
  importedRows: number;
  rejectedRows: number;
  errorCode?: string | null;
  errorMessage?: string | null;
  createdAt: string;
  completedAt?: string | null;
};

type ImportJobPage = {
  items: ImportJob[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

const errorMessage = (status: number, body: string) => {
  try {
    const problem = JSON.parse(body);
    return problem.detail ?? problem.message ?? `Import request failed (${status})`;
  } catch {
    return body || `Import request failed (${status})`;
  }
};

export function uploadImport(file: File, targetTable: string, onProgress: (progress: number) => void) {
  return new Promise<ImportJob>((resolve, reject) => {
    const request = new XMLHttpRequest();
    const body = new FormData();
    body.append('file', file);
    body.append('mappingTableName', targetTable);
    body.append('mappingResult', 'true');

    request.open('POST', '/imports');
    request.responseType = 'text';
    request.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress(Math.round((event.loaded / event.total) * 100));
    };
    request.onerror = () => reject(new Error('Unable to reach the import service.'));
    request.onload = () => {
      if (request.status < 200 || request.status >= 300) {
        reject(new Error(errorMessage(request.status, request.responseText)));
        return;
      }
      try {
        resolve(JSON.parse(request.responseText) as ImportJob);
      } catch {
        reject(new Error('The import service returned an invalid response.'));
      }
    };
    request.send(body);
  });
}

async function getJson<T>(url: string): Promise<T> {
  const response = await fetch(url);
  if (!response.ok) throw new Error(errorMessage(response.status, await response.text()));
  return response.json() as Promise<T>;
}

export const getImportJob = (jobId: number) => getJson<ImportJob>(`/imports/${jobId}`);
export const listImportJobs = (size = 20) => getJson<ImportJobPage>(`/imports?page=0&size=${size}`);

export const isTerminalImportStatus = (status: ImportStatus) =>
  status.endsWith('_FAILED') || status === 'WRITE_SUCCESS' || status === 'WRITE_FAILED' || status === 'CANCELLED';
