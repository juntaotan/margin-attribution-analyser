import React, { useEffect, useRef, useState } from 'react';
import {
  configFromGeneration,
  initialPromptFor,
  packagePrompt,
  preconfiguredPromptFor,
  tokenFromGeneration,
  userPromptWithoutDuration,
} from './placeholderTokens';
import { applyPlaceholder, generatePlaceholder } from './reportStudioApi';
import { firstResultValue, formatResultValue } from './resultFormatting';
import {
  DocumentContentControl,
  PlaceholderConfig,
  PlaceholderRun,
  PlaceholderToken,
  ReportDocument,
  SemanticExecutionPlan,
  SemanticQueryResult,
} from './types';

export const PROMPT_CHANGED_MESSAGE =
  'Prompt has changed since this result was generated. Generate again before applying.';
const SAVED_MESSAGE = 'Saved to the source document';

interface PlaceholderRunOptions {
  activeTokenId: string;
  activeControl?: DocumentContentControl;
  activeToken?: PlaceholderToken;
  persistedConfigs: Record<string, PlaceholderConfig>;
  setPersistedConfigs: React.Dispatch<React.SetStateAction<Record<string, PlaceholderConfig>>>;
  setDoc: React.Dispatch<React.SetStateAction<ReportDocument>>;
  effectiveDuration: string;
  loadContentControls: () => Promise<void>;
}

/**
 * Manages the prompt, generated blueprint, query result and apply step for the selected content control.
 *
 * Selecting a control restores its saved prompt and latest run. Generating sends the duration-packaged prompt to
 * the backend; responses to superseded requests are ignored. Applying writes the run's result into the source
 * document after the user confirms, then rescans the controls and reloads the editor.
 */
export function usePlaceholderRun({
  activeTokenId,
  activeControl,
  activeToken,
  persistedConfigs,
  setPersistedConfigs,
  setDoc,
  effectiveDuration,
  loadContentControls,
}: PlaceholderRunOptions) {
  const [promptInput, setPromptInput] = useState('');
  const [generatedSemanticPlan, setGeneratedSemanticPlan] = useState<SemanticExecutionPlan>();
  const [blueprintLoading, setBlueprintLoading] = useState(false);
  const [blueprintError, setBlueprintError] = useState<string>();
  const [queryResult, setQueryResult] = useState<SemanticQueryResult>();
  const [queryError, setQueryError] = useState<string>();
  const [activeRunId, setActiveRunId] = useState<string>();
  const [activeRunRevision, setActiveRunRevision] = useState<number>();
  const [applyLoading, setApplyLoading] = useState(false);
  const [applyError, setApplyError] = useState<string>();
  const [applySuccess, setApplySuccess] = useState<string>();
  const [editorReloadKey, setEditorReloadKey] = useState(0);
  const blueprintRequestId = useRef(0);

  const persisted = activeControl?.tag ? persistedConfigs[activeControl.tag] : undefined;
  const packagedPrompt = packagePrompt(effectiveDuration, promptInput);
  const isPromptDirty = Boolean(
    activeRunId &&
      ((persisted?.prompt && persisted.prompt.trim() !== packagedPrompt.trim()) ||
        (persisted?.revision != null && activeRunRevision != null && persisted.revision !== activeRunRevision)),
  );

  const showRun = (plan: SemanticExecutionPlan | undefined, run: PlaceholderRun | undefined) => {
    setGeneratedSemanticPlan(plan);
    setQueryResult(run?.result);
    setActiveRunId(run?.id);
    setActiveRunRevision(run?.configRevision);
    setQueryError(run?.status === 'FAILED' ? run.errorMessage : undefined);
    setApplySuccess(run?.status === 'APPLIED' ? SAVED_MESSAGE : undefined);
  };

  // Restore the selected control's prompt and its latest saved run.
  useEffect(() => {
    if (!activeControl) {
      setPromptInput('');
      showRun(undefined, undefined);
      setApplyError(undefined);
      return;
    }
    const saved = activeControl.tag ? persistedConfigs[activeControl.tag] : undefined;
    setPromptInput(userPromptWithoutDuration(initialPromptFor(activeControl, saved, activeToken)));
    if (saved) {
      showRun(saved.executionPlan, saved.lastRun);
    } else {
      showRun(activeToken?.semanticPlan, undefined);
    }
  }, [activeTokenId, persistedConfigs]);

  /** Ignores any pending generation and clears its loading state and messages. */
  const cancelBlueprint = () => {
    blueprintRequestId.current += 1;
    setBlueprintLoading(false);
    setBlueprintError(undefined);
    setApplyError(undefined);
    setApplySuccess(undefined);
  };

  /** Cancels any pending generation and discards the current blueprint and result, which no longer match. */
  const discardResult = () => {
    cancelBlueprint();
    setGeneratedSemanticPlan(undefined);
    setQueryResult(undefined);
    setQueryError(undefined);
  };

  const editPrompt = (value: string) => {
    setPromptInput(value);
    discardResult();
  };

  const resetPromptToPreconfigured = () => {
    const prompt = activeControl && preconfiguredPromptFor(activeControl);
    if (prompt) setPromptInput(prompt);
  };

  const generateBlueprint = async () => {
    if (!activeControl) return;
    const generation = { tag: activeControl.tag || activeControl.id, alias: activeControl.alias };
    discardResult();
    const requestId = blueprintRequestId.current;
    setBlueprintLoading(true);
    try {
      const response = await generatePlaceholder(generation.tag, generation.alias, packagedPrompt);
      if (blueprintRequestId.current !== requestId) return;
      setGeneratedSemanticPlan(response.blueprint);
      setActiveRunId(response.runId);
      setActiveRunRevision(response.revision);
      if (response.result) setQueryResult(response.result);
      if (response.error) setQueryError(response.error);

      const completed = { ...generation, prompt: packagedPrompt, duration: effectiveDuration, response };
      setPersistedConfigs((current) => ({ ...current, [generation.tag]: configFromGeneration(completed) }));
      setDoc((currentDoc) => ({
        ...currentDoc,
        tokens: { ...currentDoc.tokens, [activeControl.id]: tokenFromGeneration(activeControl, completed) },
      }));
    } catch (requestError) {
      if (blueprintRequestId.current !== requestId) return;
      setBlueprintError(requestError instanceof Error ? requestError.message : 'Unable to generate Blueprint');
    } finally {
      if (blueprintRequestId.current === requestId) setBlueprintLoading(false);
    }
  };

  const confirmApply = (alias: string) => {
    const value = firstResultValue(queryResult);
    const displayValue = value != null ? formatResultValue(value, generatedSemanticPlan?.format) : '';
    return window.confirm(
      `Save "${displayValue || 'result'}" to the content control with Alias "${alias}"? The document will reload.`,
    );
  };

  const applyResult = async () => {
    if (!activeRunId || !activeControl?.alias) return;
    if (isPromptDirty) {
      setApplyError(PROMPT_CHANGED_MESSAGE);
      return;
    }
    if (!confirmApply(activeControl.alias)) return;

    setApplyLoading(true);
    setApplyError(undefined);
    setApplySuccess(undefined);
    try {
      const response = await applyPlaceholder(activeRunId, activeControl.alias);
      await loadContentControls();
      setEditorReloadKey((current) => current + 1);
      const updatedControls = response.updatedControls ?? 1;
      setApplySuccess(updatedControls === 1 ? SAVED_MESSAGE : `Saved to ${updatedControls} matching controls`);
    } catch (requestError) {
      setApplyError(requestError instanceof Error ? requestError.message : 'Unable to save the result');
    } finally {
      setApplyLoading(false);
    }
  };

  return {
    persisted,
    promptInput,
    generatedSemanticPlan,
    blueprintLoading,
    blueprintError,
    queryResult,
    queryError,
    activeRunId,
    isPromptDirty,
    applyLoading,
    applyError,
    applySuccess,
    editorReloadKey,
    cancelBlueprint,
    discardResult,
    editPrompt,
    resetPromptToPreconfigured,
    generateBlueprint,
    applyResult,
  };
}
