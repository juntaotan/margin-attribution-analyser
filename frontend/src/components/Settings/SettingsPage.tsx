import React from 'react';
import { AccelerationSettings } from './AccelerationSettings';
import { AiConnectionSettings } from './AiConnectionSettings';
import { ReportTemplateSettings } from './ReportTemplateSettings';

export type { PlaceholderItem, TemplateMetadata } from './settingsApi';

/** Settings page: AI connection, management report template, and calculation and acceleration preferences. */
export const SettingsPage: React.FC = () => (
  <main className="flex-1 overflow-y-auto bg-[#f6f8fa] p-6 custom-scrollbar">
    <div className="mx-auto max-w-3xl space-y-5">
      <div className="border-b border-slate-200/80 pb-3">
        <h2 className="text-base font-bold text-slate-800">Settings &amp; Parameters</h2>
        <p className="mt-0.5 text-xs text-slate-400">
          Configure the llama.cpp connection used by all LangChain4j AI services.
        </p>
      </div>

      <AiConnectionSettings />
      <ReportTemplateSettings />
      <AccelerationSettings />
      <p className="px-1 text-[11px] leading-5 text-slate-400">
        In Docker, 127.0.0.1 points to the backend container itself. Use host.docker.internal or a network-reachable
        llama.cpp host.
      </p>
    </div>
  </main>
);
