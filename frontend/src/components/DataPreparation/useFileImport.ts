import { useCallback, useEffect, useRef, useState } from 'react';
import { MODULE_LABELS, recognizeFileName, type FileRecognition } from '../../fileRecognition';
import { getImportJob, isTerminalImportStatus, listImportJobs, uploadImport, type ImportJob } from '../../importApi';

export type ImportLogEntry = { time: string; message: string };

const logEntry = (message: string): ImportLogEntry => ({ time: new Date().toLocaleTimeString(), message });

/**
 * Keeps the import history list. The history is loaded on mount; load failures are ignored because the active
 * upload already reports connectivity errors and the user can refresh again.
 */
export function useImportHistory() {
  const [importRecords, setImportRecords] = useState<ImportJob[]>([]);

  const refreshHistory = useCallback(async () => {
    try {
      const page = await listImportJobs();
      setImportRecords(page.items);
    } catch {
      // The active upload surface reports connectivity errors; history can retry independently.
    }
  }, []);

  useEffect(() => {
    void refreshHistory();
  }, [refreshHistory]);

  /** Puts the latest state of a job at the top of the list, replacing any older entry for the same job. */
  const upsertRecord = useCallback((job: ImportJob) => {
    setImportRecords((records) => [job, ...records.filter((record) => record.jobId !== job.jobId)]);
  }, []);

  return { importRecords, refreshHistory, upsertRecord };
}

/**
 * Reads a selected spreadsheet, recognises its target table from the file name, uploads it once, and polls the
 * resulting import job every second until it reaches a terminal status.
 *
 * A new file cannot be selected while an upload or job is in progress. Every step is added to `importLog`, and
 * read, upload and job failures are reported in `fileError`.
 *
 * @param history the import history, updated with each job state received.
 */
export function useFileImport(history: ReturnType<typeof useImportHistory>) {
  const { refreshHistory, upsertRecord } = history;
  const [recognition, setRecognition] = useState<FileRecognition | null>(null);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [fileProgress, setFileProgress] = useState(0);
  const [fileReady, setFileReady] = useState(false);
  const [fileError, setFileError] = useState('');
  const [activeJob, setActiveJob] = useState<ImportJob | null>(null);
  const [isUploading, setIsUploading] = useState(false);
  const [fileSubmitted, setFileSubmitted] = useState(false);
  const [importLog, setImportLog] = useState<ImportLogEntry[]>([]);
  const importInFlight = useRef(false);
  const fileReader = useRef<FileReader | null>(null);

  const appendLog = (message: string) => setImportLog((entries) => [...entries, logEntry(message)]);
  const jobRunning = Boolean(activeJob && !isTerminalImportStatus(activeJob.status));

  const reportJobOutcome = (job: ImportJob) => {
    appendLog(
      job.status === 'WRITE_SUCCESS'
        ? `Import completed: ${job.importedRows} warehouse rows written.`
        : `Import failed at ${job.stage}: ${job.errorMessage ?? job.errorCode ?? job.status}.`,
    );
    if (job.status !== 'WRITE_SUCCESS') {
      setFileError(job.errorMessage ?? job.errorCode ?? 'Import failed.');
    }
  };

  useEffect(() => {
    if (!activeJob || isTerminalImportStatus(activeJob.status)) return;
    const poll = async () => {
      try {
        const job = await getImportJob(activeJob.jobId);
        setActiveJob(job);
        setFileProgress(job.progress);
        upsertRecord(job);
        if (isTerminalImportStatus(job.status)) {
          reportJobOutcome(job);
          void refreshHistory();
        }
      } catch (error) {
        setFileError(error instanceof Error ? error.message : 'Unable to refresh import status.');
      }
    };
    void poll();
    const timer = window.setInterval(() => void poll(), 1000);
    return () => window.clearInterval(timer);
  }, [activeJob?.jobId, activeJob?.status, refreshHistory]);

  const finishReading = (file: File) => {
    setFileProgress(100);
    setFileReady(true);
    const result = recognizeFileName(file.name);
    setRecognition(result);
    appendLog('File reading completed.');
    appendLog(
      result
        ? `Identified table: ${result.table}; module: ${MODULE_LABELS[result.module]}.`
        : 'No matching table found. Use a supported table name or alias as the file name.',
    );
  };

  const readFile = (file: File) => {
    const reader = new FileReader();
    fileReader.current = reader;
    reader.onprogress = (event) => {
      if (event.lengthComputable) setFileProgress(Math.round((event.loaded / event.total) * 100));
    };
    reader.onload = () => {
      if (fileReader.current === reader) finishReading(file);
    };
    reader.onerror = () => {
      if (fileReader.current !== reader) return;
      setFileProgress(0);
      setFileError('Unable to read file. Please try again.');
      appendLog('File reading failed. Please select the file again.');
    };
    reader.readAsArrayBuffer(file);
  };

  /** Starts over with a new file, abandoning any read still in progress. Ignored while an import is running. */
  const handleFile = (file?: File) => {
    if (!file || importInFlight.current || jobRunning) return;
    fileReader.current?.abort();
    setSelectedFile(file);
    setFileSubmitted(false);
    setActiveJob(null);
    setImportLog([logEntry(`Selected file: ${file.name}`)]);
    appendLog('Reading file…');
    setRecognition(null);
    setFileProgress(0);
    setFileReady(false);
    setFileError('');
    readFile(file);
  };

  const startImport = async (file: File, result: FileRecognition) => {
    if (importInFlight.current || fileSubmitted || jobRunning) return;
    importInFlight.current = true;
    setIsUploading(true);
    setFileError('');
    setFileProgress(0);
    appendLog(`Uploading to data lake for target table ${result.table}…`);
    try {
      const job = await uploadImport(file, result.table, setFileProgress);
      setFileSubmitted(true);
      setActiveJob(job);
      upsertRecord(job);
      setFileProgress(job.progress);
      appendLog(`Import job #${job.jobId} created. Backend pipeline started.`);
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Import failed.';
      setFileError(message);
      appendLog(message);
    } finally {
      importInFlight.current = false;
      setIsUploading(false);
    }
  };

  /** Uploads the recognised file. Ignored until the file has been read and recognised, and after submission. */
  const handleImport = () => {
    if (!fileReady || !recognition || !selectedFile || isUploading || fileSubmitted) return;
    void startImport(selectedFile, recognition);
  };

  return {
    recognition,
    selectedFile,
    fileProgress,
    fileReady,
    fileError,
    activeJob,
    jobRunning,
    isUploading,
    fileSubmitted,
    importLog,
    handleFile,
    handleImport,
  };
}
