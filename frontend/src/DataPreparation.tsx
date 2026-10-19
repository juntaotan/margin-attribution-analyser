import React, { useState } from 'react';
import { Play, Server } from 'lucide-react';
import { ImportDetailsPanel } from './components/DataPreparation/ImportDetailsPanel';
import { ImportHistoryTable } from './components/DataPreparation/ImportHistoryTable';
import { ImportSourcePanel, type DbMode } from './components/DataPreparation/ImportSourcePanel';
import { RecognitionResults } from './components/DataPreparation/RecognitionResults';
import { useFileImport, useImportHistory } from './components/DataPreparation/useFileImport';

/**
 * Data preparation page: select a spreadsheet export, review the table recognised from its file name, import it
 * into the warehouse, and follow the import job and history.
 */
export const DataPreparation: React.FC = () => {
  const [dbMode, setDbMode] = useState<DbMode>('internal');
  const history = useImportHistory();
  const fileImport = useFileImport(history);
  const { selectedFile, fileReady, isUploading, fileSubmitted, jobRunning } = fileImport;
  const selectedPartition = fileImport.recognition?.module ?? null;

  return (
    <div className="flex-1 overflow-y-auto p-6 space-y-5 custom-scrollbar bg-slate-50">
      <div className="flex items-center justify-between pb-3 border-b border-slate-200">
        <div>
          <h2 className="text-base font-bold text-slate-800">Data Preparation</h2>
          <p className="text-xs text-slate-400 mt-0.5">
            Configure the data source and import path, then review recognition results.
          </p>
        </div>

        {history.importRecords.length > 0 && (
          <button
            onClick={() => void history.refreshHistory()}
            className="text-xs text-slate-500 hover:text-slate-800 underline cursor-pointer"
          >
            Refresh Import History
          </button>
        )}
      </div>

      <div className="bg-white p-4 rounded-xl border border-slate-200/80 shadow-2xs space-y-4">
        <label className="text-xs font-bold text-slate-700 uppercase tracking-wider flex items-center gap-1.5">
          <Server className="w-4 h-4 text-sky-600" />
          1. Data Import
        </label>

        <div className={`grid grid-cols-1 gap-5 ${selectedFile ? 'lg:grid-cols-2' : ''}`}>
          <ImportSourcePanel
            dbMode={dbMode}
            onDbModeChange={setDbMode}
            selectedFile={selectedFile}
            fileReady={fileReady}
            fileProgress={fileImport.fileProgress}
            fileError={fileImport.fileError}
            isUploading={isUploading}
            activeJob={fileImport.activeJob}
            onFile={fileImport.handleFile}
          />
          {selectedFile && <ImportDetailsPanel selectedFile={selectedFile} importLog={fileImport.importLog} />}
        </div>
      </div>

      <RecognitionResults recognition={fileImport.recognition} fileReady={fileReady} />

      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={fileImport.handleImport}
          disabled={!fileReady || !selectedPartition || isUploading || fileSubmitted || jobRunning}
          className="flex items-center gap-2 px-5 py-2.5 bg-slate-900 hover:bg-slate-800 text-white text-xs font-semibold rounded-lg shadow-xs transition-all active:scale-98 cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed border border-slate-800"
        >
          <Play className="w-4 h-4 fill-white" />
          <span>
            {isUploading ? 'Uploading…' : jobRunning ? 'Processing…' : fileSubmitted ? 'Submitted' : 'Import'}
          </span>
        </button>

        <span className="text-xs text-slate-400">
          Review the recognised table, then import once. Select a file again to start another job.
        </span>
      </div>

      <ImportHistoryTable importRecords={history.importRecords} selectedPartition={selectedPartition} dbMode={dbMode} />
    </div>
  );
};
