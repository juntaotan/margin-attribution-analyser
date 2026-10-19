import React, { useState } from 'react';
import { BlueprintCard } from './BlueprintCard';
import { createDefaultDocument } from './defaultTemplate';
import { OnlyOfficeEditorPane } from './OnlyOfficeEditorPane';
import { PlaceholderList } from './PlaceholderList';
import { buildPlaceholderToken } from './placeholderTokens';
import { PromptEditor } from './PromptEditor';
import { QueryResultCard } from './QueryResultCard';
import { StudioSidebarHeader } from './StudioSidebarHeader';
import { useContentControls } from './useContentControls';
import { useDurationInsight } from './useDurationInsight';
import { usePlaceholderRun } from './usePlaceholderRun';

/** Reads the reporting period passed from the analysis page as `actualStartDate` and `actualEndDate`. */
function durationFromUrl(): string | undefined {
  const searchParams = typeof window !== 'undefined' ? new URLSearchParams(window.location.search) : null;
  const start = searchParams?.get('actualStartDate') || undefined;
  const end = searchParams?.get('actualEndDate') || undefined;
  return start && end ? `${start} to ${end}` : undefined;
}

/**
 * Report Studio: the ONLYOFFICE editor for the report document, with a sidebar that turns each document content
 * control into an AI-generated, auditable query whose result can be written back into the document.
 */
export const ReportStudioPage: React.FC = () => {
  const [doc, setDoc] = useState(createDefaultDocument);
  const controls = useContentControls();
  const { contentControls, persistedConfigs, activeTokenId } = controls;

  const placeholderTokens = contentControls.map((control) =>
    buildPlaceholderToken(
      control,
      control.tag ? persistedConfigs[control.tag] : undefined,
      doc.tokens[control.id] || (control.alias ? doc.tokens[control.alias] : undefined),
    ),
  );
  const activeToken = placeholderTokens.find((token) => token.id === activeTokenId);
  const activeControl = contentControls.find((control) => control.id === activeTokenId);

  // The duration hook must be called before the run hook so that its effects run first on each selection.
  const duration = useDurationInsight({
    activeTokenId,
    activeControl,
    activeToken,
    contentControls,
    persistedConfigs,
    documentVersion: controls.documentVersion,
    initialUrlDuration: durationFromUrl(),
  });
  const run = usePlaceholderRun({
    activeTokenId,
    activeControl,
    activeToken,
    persistedConfigs,
    setPersistedConfigs: controls.setPersistedConfigs,
    setDoc,
    effectiveDuration: duration.effectiveDuration,
    loadContentControls: controls.loadContentControls,
  });

  const selectControl = (controlId: string) => {
    run.cancelBlueprint();
    controls.setActiveTokenId(controlId);
    duration.redetectDuration();
  };

  const changeDuration = (value: string) => {
    duration.editDuration(value);
    run.discardResult();
  };

  return (
    <div className="flex h-full w-full min-h-0 overflow-hidden font-sans" style={{ backgroundColor: '#f6f8fa' }}>
      <main className="min-w-0 flex-1" style={{ backgroundColor: '#f6f8fa' }}>
        <OnlyOfficeEditorPane
          reloadKey={run.editorReloadKey}
          onDocumentChanged={() => void controls.loadContentControls()}
        />
      </main>

      <aside
        className="w-96 lg:w-[420px] xl:w-[450px] border-l border-slate-200/80 flex flex-col shrink-0 overflow-y-auto custom-scrollbar"
        style={{ backgroundColor: '#fafbfc' }}
      >
        <StudioSidebarHeader
          hasSelection={Boolean(activeControl)}
          runStatus={run.persisted?.lastRun?.status}
          executed={activeToken?.status === 'executed'}
        />

        <div className="p-4 space-y-4 flex-1">
          <PlaceholderList
            contentControls={contentControls}
            placeholderTokens={placeholderTokens}
            persistedConfigs={persistedConfigs}
            activeTokenId={activeTokenId}
            documentVersion={controls.documentVersion}
            controlsLoading={controls.controlsLoading}
            controlsError={controls.controlsError}
            onRefresh={() => void controls.loadContentControls()}
            onSelect={selectControl}
          />

          {activeToken ? (
            <>
              <PromptEditor
                tokenId={activeToken.id}
                durationInsight={duration.durationInsight}
                effectiveDuration={duration.effectiveDuration}
                promptInput={run.promptInput}
                blueprintLoading={run.blueprintLoading}
                blueprintError={run.blueprintError}
                onResetPrompt={run.resetPromptToPreconfigured}
                onDurationChange={changeDuration}
                onPromptChange={run.editPrompt}
                onGenerate={() => void run.generateBlueprint()}
              />
              {(run.queryResult || run.queryError) && (
                <QueryResultCard
                  queryResult={run.queryResult}
                  queryError={run.queryError}
                  format={run.generatedSemanticPlan?.format}
                  canApply={Boolean(run.activeRunId && activeControl?.alias)}
                  isPromptDirty={run.isPromptDirty}
                  applyLoading={run.applyLoading}
                  applySuccess={run.applySuccess}
                  applyError={run.applyError}
                  onApply={() => void run.applyResult()}
                />
              )}
              {run.generatedSemanticPlan && <BlueprintCard plan={run.generatedSemanticPlan} />}
            </>
          ) : (
            <div className="p-8 text-center text-slate-400 text-xs">Please select a data placeholder.</div>
          )}
        </div>
      </aside>
    </div>
  );
};
