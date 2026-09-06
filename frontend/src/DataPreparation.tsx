import React, { useState } from 'react';
import {
  Database,
  FolderOpen,
  Server,
  FileSpreadsheet,
  Play,
  Layers,
} from 'lucide-react';

export const DataPreparation: React.FC = () => {
  // Row 1: Partition Selection (Revenue vs Cost)
  const [selectedPartition, setSelectedPartition] = useState<'revenue' | 'cost'>('revenue');

  // Row 2: Mode Selection (Internal DB vs External DB)
  const [dbMode, setDbMode] = useState<'internal' | 'external'>('internal');
  const [importPath, setImportPath] = useState<string>('');

  // Row 4: Database Data (null = no data / pending import)
  const [hasData, setHasData] = useState<boolean>(false);

  const handleSimulateImport = () => {
    if (!importPath) {
      setImportPath('/data/workspace/margin-attribution-analyser/data_sample.csv');
    }
    setHasData(true);
  };

  const handleResetData = () => {
    setHasData(false);
    setImportPath('');
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
            Configure partition modules, database connectivity, and raw data import paths.
          </p>
        </div>

        {hasData && (
          <button
            onClick={handleResetData}
            className="text-xs text-slate-500 hover:text-slate-800 underline cursor-pointer"
          >
            Clear Data (Reset to Pending)
          </button>
        )}
      </div>

      {/* ROW 1: Partition Selection */}
      <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-xs space-y-2.5">
        <div className="flex items-center justify-between">
          <label className="text-xs font-bold text-slate-700 uppercase tracking-wider flex items-center gap-1.5">
            <Layers className="w-4 h-4 text-blue-600" />
            1. Partition Selection
          </label>
          <span className="text-[11px] text-slate-400 font-mono">
            Active: {selectedPartition === 'revenue' ? 'Revenue Module' : 'Cost Module'}
          </span>
        </div>

        <div className="grid grid-cols-2 gap-3 max-w-md">
          {/* Revenue Module */}
          <button
            type="button"
            onClick={() => setSelectedPartition('revenue')}
            className={`flex items-center justify-between p-3 rounded-lg border text-left transition-all cursor-pointer ${
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
            className={`flex items-center justify-between p-3 rounded-lg border text-left transition-all cursor-pointer ${
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

      {/* ROW 2: Mode Selection & Import Box */}
      <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-xs space-y-4">
        <label className="text-xs font-bold text-slate-700 uppercase tracking-wider flex items-center gap-1.5">
          <Server className="w-4 h-4 text-blue-600" />
          2. Mode Selection & Import Path
        </label>

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
          <div
            onClick={handleSimulateImport}
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
                  ? 'Path confirmed and ready for import'
                  : 'Import Path: [No path selected] (Supports CSV, XLSX, ERP export files)'}
              </p>
            </div>
          </div>
        </div>
      </div>

      {/* ROW 3: Import Button */}
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={handleSimulateImport}
          className="flex items-center gap-2 px-5 py-2.5 bg-blue-600 hover:bg-blue-700 text-white text-xs font-semibold rounded-lg shadow-xs transition-all active:scale-98 cursor-pointer"
        >
          <Play className="w-4 h-4 fill-white" />
          <span>Import</span>
        </button>

        <span className="text-xs text-slate-400">
          Click &quot;Import&quot; to ingest records into the selected partition database.
        </span>
      </div>

      {/* ROW 4: Database Data Display */}
      <div className="bg-white rounded-xl border border-slate-200 shadow-xs overflow-hidden">
        <div className="px-4 py-3 border-b border-slate-200 flex items-center justify-between bg-slate-50/50">
          <div className="flex items-center gap-2">
            <FileSpreadsheet className="w-4 h-4 text-blue-600" />
            <h3 className="text-xs font-bold text-slate-800 uppercase tracking-wider">
              Database Records
            </h3>
          </div>
          <span className="text-xs font-mono text-slate-400">
            Partition: {selectedPartition.toUpperCase()} | Mode: {dbMode.toUpperCase()}
          </span>
        </div>

        {/* Empty state vs Data Table */}
        {!hasData ? (
          <div className="p-12 flex flex-col items-center justify-center text-center space-y-3">
            <div className="w-12 h-12 rounded-full bg-slate-100 border border-slate-200 flex items-center justify-center text-slate-400">
              <Database className="w-6 h-6 stroke-1 text-slate-400" />
            </div>
            <div className="space-y-1">
              <h4 className="text-sm font-semibold text-slate-700">
                Pending Import
              </h4>
              <p className="text-xs text-slate-400 max-w-sm leading-relaxed">
                No database records loaded yet. Please select the target partition, specify the import path above, and click the &quot;Import&quot; button.
              </p>
            </div>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs text-left font-mono">
              <thead className="bg-slate-50 text-slate-500 border-b border-slate-200 text-[11px]">
                <tr>
                  <th className="py-2.5 px-4"># ID</th>
                  <th className="py-2.5 px-4">Partition</th>
                  <th className="py-2.5 px-4">Entity Code</th>
                  <th className="py-2.5 px-4">Description</th>
                  <th className="py-2.5 px-4 text-right">Amount (NZD)</th>
                  <th className="py-2.5 px-4 text-center">Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 text-slate-700">
                <tr>
                  <td className="py-2.5 px-4 font-bold text-slate-900">REC-001</td>
                  <td className="py-2.5 px-4 uppercase">{selectedPartition}</td>
                  <td className="py-2.5 px-4 text-blue-700">NZ-EXC-8800</td>
                  <td className="py-2.5 px-4 font-sans">Main Hydraulic Cylinder Batch #401</td>
                  <td className="py-2.5 px-4 text-right font-bold">$18,450.00</td>
                  <td className="py-2.5 px-4 text-center">
                    <span className="px-2 py-0.5 rounded bg-emerald-50 text-emerald-700 border border-emerald-200 text-[10px]">
                      Ingested
                    </span>
                  </td>
                </tr>
                <tr>
                  <td className="py-2.5 px-4 font-bold text-slate-900">REC-002</td>
                  <td className="py-2.5 px-4 uppercase">{selectedPartition}</td>
                  <td className="py-2.5 px-4 text-blue-700">STR-WLD-210</td>
                  <td className="py-2.5 px-4 font-sans">High-Yield Bisalloy Wear Plate 25mm</td>
                  <td className="py-2.5 px-4 text-right font-bold">$9,800.00</td>
                  <td className="py-2.5 px-4 text-center">
                    <span className="px-2 py-0.5 rounded bg-emerald-50 text-emerald-700 border border-emerald-200 text-[10px]">
                      Ingested
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
};
