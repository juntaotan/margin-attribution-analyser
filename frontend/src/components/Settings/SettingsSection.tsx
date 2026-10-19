import React, { useState } from 'react';
import { ChevronDown } from 'lucide-react';

interface SettingsSectionProps {
  /** Element ID of the collapsible content, referenced by the toggle's `aria-controls`. */
  contentId: string;
  icon: React.ReactNode;
  title: string;
  /** Optional element shown to the right of the title. */
  badge?: React.ReactNode;
  description: React.ReactNode;
  /** One-line status shown below the header while the section is collapsed or expanded. */
  summary: React.ReactNode;
  contentClassName?: string;
  children: React.ReactNode;
}

/** Collapsible card used for each group of settings. Sections start collapsed. */
export const SettingsSection: React.FC<SettingsSectionProps> = ({
  contentId,
  icon,
  title,
  badge,
  description,
  summary,
  contentClassName = '',
  children,
}) => {
  const [expanded, setExpanded] = useState(false);
  return (
    <section className="overflow-hidden rounded-xl border border-slate-200/80 bg-white shadow-2xs">
      <button
        type="button"
        aria-expanded={expanded}
        aria-controls={contentId}
        onClick={() => setExpanded(!expanded)}
        className="flex w-full text-left items-start gap-3 border-b border-slate-100 px-5 py-4"
      >
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-slate-200/80 bg-slate-100 text-slate-700">
          {icon}
        </div>
        <div className="flex-1">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-bold text-slate-800">{title}</h3>
            {badge}
          </div>
          <p className="mt-1 text-xs leading-5 text-slate-500">{description}</p>
        </div>
        <ChevronDown
          aria-hidden="true"
          size={16}
          className={`ml-auto shrink-0 transition-transform ${expanded ? 'rotate-180' : ''}`}
        />
      </button>
      <p className="px-5 py-2 text-xs text-slate-600">{summary}</p>
      <div id={contentId} hidden={!expanded} className={`settings-content ${contentClassName}`.trim()}>
        {children}
      </div>
    </section>
  );
};
