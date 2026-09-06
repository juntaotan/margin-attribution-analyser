import React, { useRef, useState } from 'react';
import {
  Database,
  FolderOpen,
  Server,
  FileSpreadsheet,
  Play,
  Layers,
} from 'lucide-react';

export const DataPreparation: React.FC = () => {
  type ImportRecord = {
    id: number;
    path: string;
    partition: 'revenue' | 'cost';
    mode: 'internal' | 'external';
    importedAt: string;
  };

  // Row 1: Partition Selection (Revenue vs Cost)
  const [selectedPartition, setSelectedPartition] = useState<'revenue' | 'cost' | null>(null);

  // Row 2: Mode Selection (Internal DB vs External DB)
  const [dbMode, setDbMode] = useState<'internal' | 'external'>('internal');
  const [importPath, setImportPath] = useState<string>('');

  const [importRecords, setImportRecords] = useState<ImportRecord[]>([]);

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

  const handleFile = (file?: File) => {
    if (!file) return;
    fileReader.current?.abort();
    setSelectedFile(file);
    setImportLog([{ time: new Date().toLocaleTimeString(), message: `Selected file: ${file.name}` }]);
    appendLog('Reading file…');
    setImportPath(file.name);
    setSelectedPartition(null);
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
      appendLog('File reading completed. Ready for review.');
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
    if (!fileReady || !selectedPartition) return;
    const path = importPath;
    setImportPath(path);
    setImportRecords((records) => [...records, {
      id: records.length + 1,
      path,
      partition: selectedPartition,
      mode: dbMode,
      importedAt: new Date().toLocaleString(),
    }]);
    appendLog(`Import record added for ${selectedPartition.toUpperCase()} / ${dbMode.toUpperCase()}.`);
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
            onClick={() => setImportRecords([])}
            className="text-xs text-slate-500 hover:text-slate-800 underline cursor-pointer"
          >
            Clear Import History
          </button>
        )}
      </div>

      {/* ROW 1: Mode Selection & Import Box */}
      <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-xs space-y-4">
        <label className="text-xs font-bold text-slate-700 uppercase tracking-wider flex items-center gap-1.5">
          <Server className="w-4 h-4 text-blue-600" />
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
            <label className="flex items-center gap-2 cursor-pointer text-xs font-medium text-slate-800 bg-slate-50 px-3 py-2 rounded-lg border border-slate-200 hover:bg-slate-100/70">
              <input
                type="radio"
                name="dbMode"
                checked={dbMode === 'internal'}
                onChange={() => setDbMode('internal')}
                className="text-blue-600 focus:ring-blue-500"
              />
              <span>Internal Database (Default)</span>
            </label>

            {/* External Database (Reserved) */}
            <label className="flex items-center gap-2 cursor-pointer text-xs font-medium text-slate-500 bg-slate-50 px-3 py-2 rounded-lg border border-dashed border-slate-300 hover:bg-slate-100/70">
              <input
                type="radio"
                name="dbMode"
                checked={dbMode === 'external'}
                onChange={() => setDbMode('external')}
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
            accept=".csv,.xlsx,.xls"
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
            className="border-2 border-dashed border-slate-200 rounded-xl p-5 bg-slate-50/60 hover:bg-slate-50 transition-colors flex flex-col items-center justify-center text-center space-y-2 cursor-pointer"
          >
            <div className="w-10 h-10 rounded-full bg-blue-50 border border-blue-200 flex items-center justify-center text-blue-600">
              <FolderOpen className="w-5 h-5" />
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
            <span>{fileError || (fileReady ? 'File loaded' : importPath ? 'Reading file…' : 'Waiting for file')}</span>
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
      <fieldset disabled={!fileReady} aria-label="Recognition Results" className={`min-w-0 p-4 rounded-xl border border-slate-200 shadow-xs space-y-4 ${fileReady ? 'bg-white' : 'bg-slate-100 opacity-50 grayscale'}`}>
        <div className="flex items-center justify-between">
          <label className="text-xs font-bold text-slate-700 uppercase tracking-wider flex items-center gap-1.5">
            <Layers className="w-4 h-4 text-blue-600" />
            2. Recognition Results
          </label>
          <span className="text-[11px] text-slate-400 font-mono">
            {selectedPartition ? (selectedPartition === 'revenue' ? 'Revenue Module' : 'Cost Module') : 'Not identified'}
          </span>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-[180px_1fr] gap-3 items-start">
          <span className="text-xs font-medium text-slate-500 sm:pt-3">Identified Partition</span>
        <div className="grid grid-cols-2 gap-3 max-w-md">
          {/* Revenue Module */}
          <button
            type="button"
            onClick={() => setSelectedPartition('revenue')}
            className={`flex items-center justify-between p-3 rounded-lg border text-left transition-all cursor-pointer disabled:cursor-not-allowed ${
              selectedPartition === 'revenue'
                ? 'bg-blue-50 border-blue-500 text-blue-900 ring-1 ring-blue-500/30'
                : 'bg-white border-slate-200 text-slate-700 hover:bg-slate-50'
            }`}
          >
            <div>
              <span className="text-xs font-bold block">Revenue Module</span>
              <span className="text-[11px] text-slate-400 block mt-0.5">Sales orders and accounts receivable</span>
            </div>
            <span
              className={`w-3.5 h-3.5 rounded-full border-2 flex items-center justify-center ${
                selectedPartition === 'revenue'
                  ? 'border-blue-600 bg-blue-600'
                  : 'border-slate-300'
              }`}
            >
              {selectedPartition === 'revenue' && (
                <span className="w-1.5 h-1.5 rounded-full bg-white" />
              )}
            </span>
          </button>

          {/* Cost Module */}
          <button
            type="button"
            onClick={() => setSelectedPartition('cost')}
            className={`flex items-center justify-between p-3 rounded-lg border text-left transition-all cursor-pointer disabled:cursor-not-allowed ${
              selectedPartition === 'cost'
                ? 'bg-blue-50 border-blue-500 text-blue-900 ring-1 ring-blue-500/30'
                : 'bg-white border-slate-200 text-slate-700 hover:bg-slate-50'
            }`}
          >
            <div>
              <span className="text-xs font-bold block">Cost Module</span>
              <span className="text-[11px] text-slate-400 block mt-0.5">Material, labor, and machine overhead</span>
            </div>
            <span
              className={`w-3.5 h-3.5 rounded-full border-2 flex items-center justify-center ${
                selectedPartition === 'cost'
                  ? 'border-blue-600 bg-blue-600'
                  : 'border-slate-300'
              }`}
            >
              {selectedPartition === 'cost' && (
                <span className="w-1.5 h-1.5 rounded-full bg-white" />
              )}
            </span>
          </button>
        </div>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-[180px_1fr] gap-3 items-center border-t border-slate-100 pt-4">
          <span className="text-xs font-medium text-slate-500">Target Table Mapping</span>
          <div className="max-w-md rounded-lg border border-slate-200 bg-slate-50 px-3 py-2.5 text-xs text-slate-400">
            Pending identification
          </div>
        </div>
      </fieldset>

      {/* ROW 3: Import Button */}
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={handleImport}
          disabled={!fileReady || !selectedPartition}
          className="flex items-center gap-2 px-5 py-2.5 bg-blue-600 hover:bg-blue-700 text-white text-xs font-semibold rounded-lg shadow-xs transition-all active:scale-98 cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <Play className="w-4 h-4 fill-white" />
          <span>Import</span>
        </button>

        <span className="text-xs text-slate-400">
          Click &quot;Import&quot; to ingest records into the selected partition database.
        </span>
      </div>

      {/* ROW 4: Import History */}
      <div className="bg-white rounded-xl border border-slate-200 shadow-xs overflow-hidden">
        <div className="px-4 py-3 border-b border-slate-200 flex items-center justify-between bg-slate-50/50">
          <div className="flex items-center gap-2">
            <FileSpreadsheet className="w-4 h-4 text-blue-600" />
            <h3 className="text-xs font-bold text-slate-800 uppercase tracking-wider">
              Import Records
            </h3>
          </div>
          <span className="text-xs font-mono text-slate-400">
            Partition: {selectedPartition?.toUpperCase() ?? '—'} | Mode: {dbMode.toUpperCase()}
          </span>
        </div>

        {importRecords.length === 0 ? (
          <div className="p-12 flex flex-col items-center justify-center text-center space-y-3">
            <div className="w-12 h-12 rounded-full bg-slate-100 border border-slate-200 flex items-center justify-center text-slate-400">
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
              <thead className="bg-slate-50 text-slate-500 border-b border-slate-200 text-[11px]">
                <tr>
                  <th className="py-2.5 px-4">#</th>
                  <th className="py-2.5 px-4">File Path</th>
                  <th className="py-2.5 px-4">Partition</th>
                  <th className="py-2.5 px-4">Mode</th>
                  <th className="py-2.5 px-4">Imported At</th>
                  <th className="py-2.5 px-4 text-center">Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 text-slate-700">
                {importRecords.map((record) => (
                  <tr key={record.id}>
                    <td className="py-2.5 px-4 font-bold text-slate-900">{record.id}</td>
                    <td className="py-2.5 px-4 text-blue-700">{record.path}</td>
                    <td className="py-2.5 px-4 uppercase">{record.partition}</td>
                    <td className="py-2.5 px-4 uppercase">{record.mode}</td>
                    <td className="py-2.5 px-4">{record.importedAt}</td>
                    <td className="py-2.5 px-4 text-center">
                      <span className="px-2 py-0.5 rounded bg-emerald-50 text-emerald-700 border border-emerald-200 text-[10px]">
                        Imported
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
