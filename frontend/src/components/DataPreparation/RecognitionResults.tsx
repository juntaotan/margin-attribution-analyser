import React from 'react';
import { Layers } from 'lucide-react';
import { MODULE_LABELS, type BusinessModule, type FileRecognition } from '../../fileRecognition';

/** Shows the business module and target table recognised from the file name. Disabled until the file is read. */
export const RecognitionResults: React.FC<{ recognition: FileRecognition | null; fileReady: boolean }> = ({
  recognition,
  fileReady,
}) => {
  const selectedPartition = recognition?.module ?? null;
  return (
    <fieldset
      disabled={!fileReady}
      aria-label="Recognition Results"
      className={`min-w-0 p-4 rounded-xl border border-slate-200/80 shadow-2xs space-y-4 ${fileReady ? 'bg-white' : 'bg-slate-100 opacity-50 grayscale'}`}
    >
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
        <div
          className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-3"
          role="group"
          aria-label="Identified module"
        >
          {(Object.entries(MODULE_LABELS) as [BusinessModule, string][]).map(([module, label]) => (
            <label
              key={module}
              className={`flex items-center justify-between gap-3 p-3 rounded-lg border text-xs font-semibold ${selectedPartition === module ? 'bg-sky-50 border-sky-500 text-sky-950 ring-1 ring-sky-500/30' : 'bg-[#f8fafc] border-slate-200/80 text-slate-600'}`}
            >
              {label}
              <input
                type="radio"
                name="identified-module"
                checked={selectedPartition === module}
                disabled
                aria-label={label}
                className="accent-sky-600"
              />
            </label>
          ))}
        </div>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-[180px_1fr] gap-3 items-center border-t border-slate-100 pt-4">
        <span className="text-xs font-medium text-slate-500">Target Table Mapping</span>
        <div className="max-w-md rounded-lg border border-slate-200/80 bg-[#f8fafc] px-3 py-2.5 text-xs text-slate-500">
          {recognition?.table ??
            (fileReady
              ? 'No matching table — rename the file to a supported table name or alias.'
              : 'Pending identification')}
        </div>
      </div>
    </fieldset>
  );
};
