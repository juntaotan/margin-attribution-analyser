import React, { useCallback, useEffect, useRef, useState } from 'react';
import { MODULE_LABELS, TABLE_PRESETS, recognizeFileName, type BusinessModule, type FileRecognition } from './fileRecognition';
import { getImportJob, isTerminalImportStatus, listImportJobs, uploadImport, type ImportJob } from './importApi';
import {
  Database,
  FolderOpen,
  Server,
  FileSpreadsheet,
  Play,
  Layers,
} from 'lucide-react';

export const DataPreparation: React.FC = () => {
  const [recognition, setRecognition] = useState<FileRecognition | null>(null);
  const selectedPartition = recognition?.module ?? null;

  // Row 2: Mode Selection (Internal DB vs External DB)
  const [dbMode, setDbMode] = useState<'internal' | 'external'>('internal');
  const [importPath, setImportPath] = useState<string>('');

  const [importRecords, setImportRecords] = useState<ImportJob[]>([]);
  const [activeJob, setActiveJob] = useState<ImportJob | null>(null);
  const [isUploading, setIsUploading] = useState(false);

  const fileInput = useRef<HTMLInputElement>(null);
  const [fileProgress, setFileProgress] = useState(0);
  const [fileReady, setFileReady] = useState(false);
  const [fileError, setFileError] = useState('');
  const fileReader = useRef<FileReader | null>(null);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [detailTab, setDetailTab] = useState<'log' | 'file'>('log');
  const [importLog, setImportLog] = useState<{ time: string; message: string }[]>([]);

  const appendLog = (message: string) => {
    setImportLog((entries) => [...entries, { time: new Date().toLocaleTimeString(), message }]);
  };

  const formatFileSize = (bytes: number) => {
    if (bytes < 1024) return `${bytes} B`;
    const unit = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), 3);
    return `${(bytes / 1024 ** unit).toFixed(2)} ${['B', 'KB', 'MB', 'GB'][unit]} (${bytes.toLocaleString()} bytes)`;
  };

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

  useEffect(() => {
    if (!activeJob || isTerminalImportStatus(activeJob.status)) return;
    const poll = async () => {
      try {
        const job = await getImportJob(activeJob.jobId);
        setActiveJob(job);
        setFileProgress(job.progress);
        setImportRecords((records) => [job, ...records.filter((record) => record.jobId !== job.jobId)]);
        if (isTerminalImportStatus(job.status)) {
          appendLog(job.status === 'WRITE_SUCCESS'
            ? `Import completed: ${job.importedRows} warehouse rows written.`
            : `Import failed at ${job.stage}: ${job.errorMessage ?? job.errorCode ?? job.status}.`);
          if (job.status !== 'WRITE_SUCCESS') {
            setFileError(job.errorMessage ?? job.errorCode ?? 'Import failed.');
          }
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

  const startImport = async (file: File, result: FileRecognition) => {
    setIsUploading(true);
    setFileError('');
    setFileProgress(0);
    appendLog(`Uploading to data lake for target table ${result.table}…`);
    try {
      const job = await uploadImport(file, result.table, setFileProgress);
      setActiveJob(job);
      setImportRecords((records) => [job, ...records.filter((record) => record.jobId !== job.jobId)]);
      setFileProgress(job.progress);
      appendLog(`Import job #${job.jobId} created. Backend pipeline started.`);
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Import failed.';
      setFileError(message);
      appendLog(message);
    } finally {
      setIsUploading(false);
    }
  };

  const handleFile = (file?: File) => {
    if (!file) return;
    fileReader.current?.abort();
    setSelectedFile(file);
    setImportLog([{ time: new Date().toLocaleTimeString(), message: `Selected file: ${file.name}` }]);
    appendLog('Reading file…');
    setImportPath(file.name);
    setRecognition(null);
    setFileProgress(0);
    setFileReady(false);
    setFileError('');
    const reader = new FileReader();
    fileReader.current = reader;
    reader.onprogress = (event) => {
      if (event.lengthComputable) setFileProgress(Math.round(event.loaded / event.total * 100));
    };
    reader.onload = () => {
      if (fileReader.current !== reader) return;
      setFileProgress(100);
      setFileReady(true);
      const result = recognizeFileName(file.name);
      setRecognition(result);
      appendLog('File reading completed.');
      appendLog(result
        ? `Identified table: ${result.table}; module: ${MODULE_LABELS[result.module]}.`
        : 'No matching table found. Use a supported table name or alias as the file name.');
      if (result) {
        void startImport(file, result);
      }
    };
    reader.onerror = () => {
      if (fileReader.current !== reader) return;
      setFileProgress(0);
      setFileError('Unable to read file. Please try again.');
      appendLog('File reading failed. Please select the file again.');
    };
    reader.readAsArrayBuffer(file);
  };

  const handleImport = () => {
    if (!fileReady || !recognition || !selectedFile || isUploading) return;
    void startImport(selectedFile, recognition);
  };

  return (
    <div className="flex-1 overflow-y-auto p-6 space-y-5 custom-scrollbar bg-slate-50">
      {/* Title & Description */}
      <div className="flex items-center justify-between pb-3 border-b border-slate-200">
        <div>
          <h2 className="text-base font-bold text-slate-800">
            Data Preparation
          </h2>
          <p className="text-xs text-slate-400 mt-0.5">
            Configure the data source and import path, then review recognition results.
          </p>
        </div>

        {importRecords.length > 0 && (
          <button
            onClick={() => void refreshHistory()}
            className="text-xs text-slate-500 hover:text-slate-800 underline cursor-pointer"
          >
            Refresh Import History
          </button>
        )}
      </div>

      {/* ROW 1: Mode Selection & Import Box */}
      <div className="bg-white p-4 rounded-xl border border-slate-200/80 shadow-2xs space-y-4">
        <label className="text-xs font-bold text-slate-700 uppercase tracking-wider flex items-center gap-1.5">
          <Server className="w-4 h-4 text-sky-600" />
          1. Data Import
        </label>

        <div className={`grid grid-cols-1 gap-5 ${selectedFile ? 'lg:grid-cols-2' : ''}`}>
        <div className="min-w-0 space-y-4">
        {/* Mode Selection */}
        <div className="space-y-2">
          <span className="text-xs font-medium text-slate-500 block">
            Database Source Mode:
          </span>
          <div className="flex flex-wrap gap-4">
            {/* Internal Database (Default) */}
            <label className="flex items-center gap-2 cursor-pointer text-xs font-medium text-slate-800 bg-[#f8fafc] px-3 py-2 rounded-lg border border-slate-200/80 hover:bg-slate-100/70">
              <input
                type="radio"
                name="dbMode"
                checked={dbMode === 'internal'}
                onChange={() => setDbMode('internal')}
                className="text-sky-600 focus:ring-sky-500"
              />
              <span>Internal Database (Default)</span>
            </label>

            {/* External Database (Reserved) */}
            <label className="flex items-center gap-2 cursor-pointer text-xs font-medium text-slate-500 bg-[#f8fafc] px-3 py-2 rounded-lg border border-dashed border-slate-300/80 hover:bg-slate-100/70">
              <input
                type="radio"
                name="dbMode"
                checked={dbMode === 'external'}
                onChange={() => setDbMode('external')}
                disabled
                className="text-blue-600 focus:ring-blue-500"
              />
              <span>External Existing Database (Reserved)</span>
            </label>
          </div>
        </div>

        {/* Import Path Dropzone Box */}
        <div className="space-y-1.5">
          <span className="text-xs font-medium text-slate-500 block">
            Import File Path & Target Zone:
          </span>
          <input
            ref={fileInput}
            type="file"
            accept=".xlsx,.xls"
            className="hidden"
            aria-label="Select import file"
            onChange={(event) => {
              handleFile(event.target.files?.[0]);
              event.target.value = '';
            }}
          />
          <div
            role="button"
            tabIndex={0}
            aria-label="Choose or drop import file"
            onClick={() => fileInput.current?.click()}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                fileInput.current?.click();
              }
            }}
            onDragOver={(event) => event.preventDefault()}
            onDrop={(event) => {
              event.preventDefault();
              handleFile(event.dataTransfer.files[0]);
            }}
            className="border-2 border-dashed border-slate-300/80 rounded-xl p-5 bg-[#f8fafc] hover:bg-slate-100/50 transition-colors flex flex-col items-center justify-center text-center space-y-2 cursor-pointer"
          >
            <div className="w-10 h-10 rounded-full bg-slate-100 border border-slate-200/80 flex items-center justify-center text-slate-700">
              <FolderOpen className="w-5 h-5 text-sky-600" />
            </div>

            <div className="space-y-1">
              <div className="text-xs font-semibold text-slate-700">
                {importPath ? (
                  <span className="text-blue-700 font-mono break-all">{importPath}</span>
                ) : (
                  <span>Click or drag file here to set import path</span>
                )}
              </div>
              <p className="text-[11px] text-slate-400 font-mono">
                {importPath
                  ? (fileReady ? 'File loaded' : 'Reading file…')
                  : 'Import Path: [No path selected] (Supports CSV, XLSX, ERP export files)'}
              </p>
            </div>
          </div>
        </div>
        <div className="space-y-2" aria-live="polite">
          <div className="flex justify-between text-xs text-slate-500">
            <span>{fileError || (isUploading ? 'Uploading to data lake…' : activeJob ? activeJob.stage.replace(/_/g, ' ') : fileReady ? 'File loaded' : importPath ? 'Reading file…' : 'Waiting for file')}</span>
            <span className="font-mono">{fileProgress}%</span>
          </div>
          <div role="progressbar" aria-label="File loading progress" aria-valuemin={0} aria-valuemax={100} aria-valuenow={fileProgress} className="h-2 overflow-hidden rounded-full bg-slate-200">
            <div className="h-full rounded-full bg-blue-600 transition-all" style={{ width: `${fileProgress}%` }} />
          </div>
        </div>
        </div>
        {selectedFile && <aside className="min-w-0 rounded-xl border border-slate-200 bg-slate-50/50 overflow-hidden">
          <div role="tablist" aria-label="Import details" className="flex border-b border-slate-200 bg-slate-100/70">
            {(['log', 'file'] as const).map((tab) => (
              <button
                key={tab}
                type="button"
                role="tab"
                id={`import-tab-${tab}`}
                aria-selected={detailTab === tab}
                aria-controls={`import-panel-${tab}`}
                tabIndex={detailTab === tab ? 0 : -1}
                onClick={() => setDetailTab(tab)}
                onKeyDown={(event) => {
                  if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
                    event.preventDefault();
                    const next = event.key === 'Home' ? 'log' : event.key === 'End' ? 'file' : tab === 'log' ? 'file' : 'log';
                    setDetailTab(next);
                    document.getElementById(`import-tab-${next}`)?.focus();
                  }
                }}
                className={`px-4 py-3 text-xs font-semibold border-b-2 transition-colors ${detailTab === tab ? 'border-blue-600 text-blue-700 bg-white' : 'border-transparent text-slate-500 hover:text-slate-700'}`}
              >
                {tab === 'log' ? 'Import Log' : 'File Information'}
              </button>
            ))}
          </div>
          <div role="tabpanel" id="import-panel-log" aria-labelledby="import-tab-log" hidden={detailTab !== 'log'} tabIndex={0} className="p-4">
            <div role="log" aria-label="File import activity" className="max-h-64 overflow-y-auto space-y-3 text-xs">
              {importLog.length === 0 ? (
                <p className="text-slate-400">Select a file to view import activity.</p>
              ) : importLog.map((entry, index) => (
                <div key={index} className="flex gap-3 items-start">
                  <time className="shrink-0 font-mono text-slate-400">{entry.time}</time>
                  <span className="text-slate-600 break-all">{entry.message}</span>
                </div>
              ))}
            </div>
          </div>
          <div role="tabpanel" id="import-panel-file" aria-labelledby="import-tab-file" hidden={detailTab !== 'file'} tabIndex={0} className="p-4">
            {selectedFile ? (
              <dl className="text-xs divide-y divide-slate-200">
                <div className="py-3 first:pt-0 space-y-1.5">
                  <dt className="text-slate-500">File Name</dt>
                  <dd className="font-medium text-slate-800 break-all">{selectedFile.name}</dd>
                </div>
                <div className="py-3 space-y-1.5">
                  <dt className="text-slate-500">File Size</dt>
                  <dd className="font-mono text-slate-800">{formatFileSize(selectedFile.size)}</dd>
                </div>
                <div className="py-3 space-y-1.5">
                  <dt className="text-slate-500">File Location</dt>
                  <dd className="text-slate-800 break-all">{selectedFile.webkitRelativePath || 'Local device — full path unavailable in browser'}</dd>
                </div>
              </dl>
            ) : (
              <p className="text-xs text-slate-400">No file selected.</p>
            )}
          </div>
        </aside>}
        </div>
      </div>

      {/* ROW 2: Recognition Results */}
      <fieldset disabled={!fileReady} aria-label="Recognition Results" className={`min-w-0 p-4 rounded-xl border border-slate-200/80 shadow-2xs space-y-4 ${fileReady ? 'bg-white' : 'bg-slate-100 opacity-50 grayscale'}`}>
        <div className="flex items-center justify-between">
          <label className="text-xs font-bold text-slate-700 uppercase tracking-wider flex items-center gap-1.5">
            <Layers className="w-4 h-4 text-sky-600" />
            2. Recognition Results
          </label>
          <span className="text-[11px] text-slate-400 font-mono">
            {selectedPartition ? MODULE_LABELS[selectedPartition] : 'Not identified'}
          </span>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-[180px_1fr] gap-3 items-start">
          <span className="text-xs font-medium text-slate-500 sm:pt-3">Identified Module</span>
          <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-3" role="group" aria-label="Identified module">
            {(Object.entries(MODULE_LABELS) as [BusinessModule, string][]).map(([module, label]) => (
              <label key={module} className={`flex items-center justify-between gap-3 p-3 rounded-lg border text-xs font-semibold ${selectedPartition === module ? 'bg-sky-50 border-sky-500 text-sky-950 ring-1 ring-sky-500/30' : 'bg-[#f8fafc] border-slate-200/80 text-slate-600'}`}>
                {label}
                <input type="radio" name="identified-module" checked={selectedPartition === module} disabled aria-label={label} className="accent-sky-600" />
              </label>
            ))}
          </div>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-[180px_1fr] gap-3 items-center border-t border-slate-100 pt-4">
          <span className="text-xs font-medium text-slate-500">Target Table Mapping</span>
          <div className="max-w-md rounded-lg border border-slate-200/80 bg-[#f8fafc] px-3 py-2.5 text-xs text-slate-500">
            {recognition?.table ?? (fileReady ? 'No matching table — rename the file to a supported table name or alias.' : 'Pending identification')}
          </div>
        </div>
      </fieldset>

      {/* ROW 3: Import Button */}
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={handleImport}
          disabled={!fileReady || !selectedPartition || isUploading || Boolean(activeJob && !isTerminalImportStatus(activeJob.status))}
          className="flex items-center gap-2 px-5 py-2.5 bg-slate-900 hover:bg-slate-800 text-white text-xs font-semibold rounded-lg shadow-xs transition-all active:scale-98 cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed border border-slate-800"
        >
          <Play className="w-4 h-4 fill-white" />
          <span>{isUploading ? 'Uploading…' : activeJob && !isTerminalImportStatus(activeJob.status) ? 'Processing…' : 'Import Again'}</span>
        </button>

        <span className="text-xs text-slate-400">
          Recognized files start automatically; this button retries the selected file.
        </span>
      </div>

      {/* ROW 4: Import History */}
      <div className="bg-white rounded-xl border border-slate-200/80 shadow-2xs overflow-hidden">
        <div className="px-4 py-3 border-b border-slate-200/80 flex items-center justify-between bg-[#f8fafc]">
          <div className="flex items-center gap-2">
            <FileSpreadsheet className="w-4 h-4 text-sky-600" />
            <h3 className="text-xs font-bold text-slate-800 uppercase tracking-wider">
              Import Records
            </h3>
          </div>
          <span className="text-xs font-mono text-slate-400">
            Module: {selectedPartition?.toUpperCase() ?? '—'} | Mode: {dbMode.toUpperCase()}
          </span>
        </div>

        {importRecords.length === 0 ? (
          <div className="p-12 flex flex-col items-center justify-center text-center space-y-3">
            <div className="w-12 h-12 rounded-full bg-slate-100 border border-slate-200/80 flex items-center justify-center text-slate-400">
              <Database className="w-6 h-6 stroke-1 text-slate-400" />
            </div>
            <div className="space-y-1">
              <h4 className="text-sm font-semibold text-slate-700">
                No Import Records
              </h4>
              <p className="text-xs text-slate-400 max-w-sm leading-relaxed">
                Completed imports will be listed here.
              </p>
            </div>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs text-left font-mono">
              <thead className="bg-[#f1f5f9] text-slate-600 border-b border-slate-200 text-[11px]">
                <tr>
                  <th className="py-2.5 px-4">#</th>
                  <th className="py-2.5 px-4">File</th>
                  <th className="py-2.5 px-4">Module</th>
                  <th className="py-2.5 px-4">Target Table</th>
                  <th className="py-2.5 px-4">Created At</th>
                  <th className="py-2.5 px-4 text-center">Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 text-slate-700">
                {importRecords.map((record) => (
                  <tr key={record.jobId} title={record.errorMessage ?? undefined}>
                    <td className="py-2.5 px-4 font-bold text-slate-900">{record.jobId}</td>
                    <td className="py-2.5 px-4 text-blue-700">{record.filename}</td>
                    <td className="py-2.5 px-4 uppercase">
                      {TABLE_PRESETS.find((preset) => preset.table === record.targetTable)?.module ?? '—'}
                    </td>
                    <td className="py-2.5 px-4">{record.targetTable}</td>
                    <td className="py-2.5 px-4">{new Date(record.createdAt).toLocaleString()}</td>
                    <td className="py-2.5 px-4 text-center">
                      <span className={`px-2 py-0.5 rounded border text-[10px] ${record.status === 'WRITE_SUCCESS'
                        ? 'bg-emerald-50 text-emerald-800 border-emerald-200/80'
                        : isTerminalImportStatus(record.status)
                          ? 'bg-rose-50 text-rose-700 border-rose-200/80'
                          : 'bg-sky-50 text-sky-800 border-sky-200/80'}`}>
                        {record.status === 'WRITE_SUCCESS' ? `Imported (${record.importedRows})` : record.stage.replace(/_/g, ' ')}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
};
