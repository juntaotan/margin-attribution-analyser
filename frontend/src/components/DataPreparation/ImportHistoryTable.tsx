import React from 'react';
import { Database, FileSpreadsheet } from 'lucide-react';
import { TABLE_PRESETS, type BusinessModule } from '../../fileRecognition';
import { isTerminalImportStatus, type ImportJob } from '../../importApi';

interface ImportHistoryTableProps {
  importRecords: ImportJob[];
  selectedPartition: BusinessModule | null;
  dbMode: string;
}

/** Lists recent import jobs with their target table and current status. */
export const ImportHistoryTable: React.FC<ImportHistoryTableProps> = ({ importRecords, selectedPartition, dbMode }) => (
  <div className="bg-white rounded-xl border border-slate-200/80 shadow-2xs overflow-hidden">
    <div className="px-4 py-3 border-b border-slate-200/80 flex items-center justify-between bg-[#f8fafc]">
      <div className="flex items-center gap-2">
        <FileSpreadsheet className="w-4 h-4 text-sky-600" />
        <h3 className="text-xs font-bold text-slate-800 uppercase tracking-wider">Import Records</h3>
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
          <h4 className="text-sm font-semibold text-slate-700">No Import Records</h4>
          <p className="text-xs text-slate-400 max-w-sm leading-relaxed">Completed imports will be listed here.</p>
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
              <ImportHistoryRow key={record.jobId} record={record} />
            ))}
          </tbody>
        </table>
      </div>
    )}
  </div>
);

const ImportHistoryRow: React.FC<{ record: ImportJob }> = ({ record }) => (
  <tr title={record.errorMessage ?? undefined}>
    <td className="py-2.5 px-4 font-bold text-slate-900">{record.jobId}</td>
    <td className="py-2.5 px-4 text-blue-700">{record.filename}</td>
    <td className="py-2.5 px-4 uppercase">
      {TABLE_PRESETS.find((preset) => preset.table === record.targetTable)?.module ?? '—'}
    </td>
    <td className="py-2.5 px-4">{record.targetTable}</td>
    <td className="py-2.5 px-4">{new Date(record.createdAt).toLocaleString()}</td>
    <td className="py-2.5 px-4 text-center">
      <span
        className={`px-2 py-0.5 rounded border text-[10px] ${
          record.status === 'WRITE_SUCCESS'
            ? 'bg-emerald-50 text-emerald-800 border-emerald-200/80'
            : isTerminalImportStatus(record.status)
              ? 'bg-rose-50 text-rose-700 border-rose-200/80'
              : 'bg-sky-50 text-sky-800 border-sky-200/80'
        }`}
      >
        {record.status === 'WRITE_SUCCESS' ? `Imported (${record.importedRows})` : record.stage.replace(/_/g, ' ')}
      </span>
    </td>
  </tr>
);
