import React, { useRef } from 'react';
import { FolderOpen } from 'lucide-react';
import { isTerminalImportStatus, type ImportJob } from '../../importApi';

export type DbMode = 'internal' | 'external';

interface ImportSourcePanelProps {
  dbMode: DbMode;
  onDbModeChange: (mode: DbMode) => void;
  selectedFile: File | null;
  fileReady: boolean;
  fileProgress: number;
  fileError: string;
  isUploading: boolean;
  activeJob: ImportJob | null;
  onFile: (file?: File) => void;
}

/** Database source mode, file drop zone, and the progress of reading, uploading and importing the file. */
export const ImportSourcePanel: React.FC<ImportSourcePanelProps> = ({
  dbMode,
  onDbModeChange,
  selectedFile,
  fileReady,
  onFile,
  ...progress
}) => (
  <div className="min-w-0 space-y-4">
    <div className="space-y-2">
      <span className="text-xs font-medium text-slate-500 block">Database Source Mode:</span>
      <div className="flex flex-wrap gap-4">
        <label className="flex items-center gap-2 cursor-pointer text-xs font-medium text-slate-800 bg-[#f8fafc] px-3 py-2 rounded-lg border border-slate-200/80 hover:bg-slate-100/70">
          <input
            type="radio"
            name="dbMode"
            checked={dbMode === 'internal'}
            onChange={() => onDbModeChange('internal')}
            className="text-sky-600 focus:ring-sky-500"
          />
          <span>Internal Database (Default)</span>
        </label>

        {/* External databases are reserved for a future release. */}
        <label className="flex items-center gap-2 cursor-pointer text-xs font-medium text-slate-500 bg-[#f8fafc] px-3 py-2 rounded-lg border border-dashed border-slate-300/80 hover:bg-slate-100/70">
          <input
            type="radio"
            name="dbMode"
            checked={dbMode === 'external'}
            onChange={() => onDbModeChange('external')}
            disabled
            className="text-blue-600 focus:ring-blue-500"
          />
          <span>External Existing Database (Reserved)</span>
        </label>
      </div>
    </div>

    <FileDropZone importPath={selectedFile?.name ?? ''} fileReady={fileReady} onFile={onFile} />
    <ImportProgress importPath={selectedFile?.name ?? ''} fileReady={fileReady} {...progress} />
  </div>
);

const FileDropZone: React.FC<{ importPath: string; fileReady: boolean; onFile: (file?: File) => void }> = ({
  importPath,
  fileReady,
  onFile,
}) => {
  const fileInput = useRef<HTMLInputElement>(null);
  return (
    <div className="space-y-1.5">
      <span className="text-xs font-medium text-slate-500 block">Import File Path & Target Zone:</span>
      <input
        ref={fileInput}
        type="file"
        accept=".xlsx,.xls"
        className="hidden"
        aria-label="Select import file"
        onChange={(event) => {
          onFile(event.target.files?.[0]);
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
          onFile(event.dataTransfer.files[0]);
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
              ? fileReady
                ? 'File loaded'
                : 'Reading file…'
              : 'Import Path: [No path selected] (Supports XLSX and XLS exports)'}
          </p>
        </div>
      </div>
    </div>
  );
};

interface ImportProgressProps {
  importPath: string;
  fileReady: boolean;
  fileProgress: number;
  fileError: string;
  isUploading: boolean;
  activeJob: ImportJob | null;
}

function progressLabel({ importPath, fileReady, fileError, isUploading, activeJob }: ImportProgressProps): string {
  if (fileError) return fileError;
  if (isUploading) return 'Uploading to data lake…';
  if (activeJob) return activeJob.stage.replace(/_/g, ' ');
  if (fileReady) return 'File loaded';
  return importPath ? 'Reading file…' : 'Waiting for file';
}

/**
 * Shows byte progress while a file is read or uploaded, and stage progress once the backend job exists.
 * Stage progress has no numeric value until the job succeeds.
 */
const ImportProgress: React.FC<ImportProgressProps> = (props) => {
  const { fileProgress, isUploading, activeJob } = props;
  const showingStages = Boolean(activeJob && !isUploading);
  return (
    <div className="space-y-2" aria-live="polite">
      <div className="flex justify-between text-xs text-slate-500">
        <span>{progressLabel(props)}</span>
        <span className="font-mono">
          {showingStages
            ? activeJob?.status === 'WRITE_SUCCESS'
              ? `${activeJob.importedRows} rows imported`
              : 'Stage progress'
            : `${fileProgress}%`}
        </span>
      </div>
      <div
        role="progressbar"
        aria-label={showingStages ? 'Import stages' : 'File transfer progress'}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={showingStages && activeJob?.status !== 'WRITE_SUCCESS' ? undefined : fileProgress}
        aria-valuetext={activeJob?.stage}
        className="h-2 overflow-hidden rounded-full bg-slate-200"
      >
        <div
          className={`h-full rounded-full bg-[#466987] transition-[width] duration-150 ${activeJob && !isTerminalImportStatus(activeJob.status) ? 'import-stage-motion' : ''}`}
          style={{ width: `${fileProgress}%` }}
        />
      </div>
    </div>
  );
};
