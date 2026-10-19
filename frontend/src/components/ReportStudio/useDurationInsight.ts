import { useEffect, useRef, useState } from 'react';
import { DEFAULT_DURATION, durationFromPrompt, initialPromptFor } from './placeholderTokens';
import { detectDuration } from './reportStudioApi';
import { DocumentContentControl, PlaceholderConfig, PlaceholderToken } from './types';

/** Reporting-period state shown above the prompt editor. */
export interface DurationInsight {
  status: 'idle' | 'loading' | 'ready' | 'error';
  duration: string;
  sentences: string[];
  message?: string;
}

interface DurationInsightOptions {
  activeTokenId: string;
  activeControl?: DocumentContentControl;
  activeToken?: PlaceholderToken;
  contentControls: DocumentContentControl[];
  persistedConfigs: Record<string, PlaceholderConfig>;
  documentVersion?: number;
  /** Period passed from the analysis page through the URL; it takes priority over every other source. */
  initialUrlDuration?: string;
}

const NO_SELECTION: DurationInsight = { status: 'idle', duration: 'Select a tagged placeholder', sentences: [] };

/**
 * Determines the reporting period for the selected content control.
 *
 * On selection the period is taken, in order, from the URL, an earlier edit, the saved configuration, the prompt's
 * `Duration:` line or {@link DEFAULT_DURATION}. When the saved configuration has no period, the backend is then
 * asked to detect it from the document text, and a detected period replaces the provisional one. Stale and aborted
 * detections are ignored.
 *
 * @returns the insight, the period currently in effect, and handlers to edit it or repeat detection.
 */
export function useDurationInsight({
  activeTokenId,
  activeControl,
  activeToken,
  contentControls,
  persistedConfigs,
  documentVersion,
  initialUrlDuration,
}: DurationInsightOptions) {
  const [durationInsight, setDurationInsight] = useState<DurationInsight>(NO_SELECTION);
  const [durationOverrides, setDurationOverrides] = useState<Record<string, string>>({});
  const [detectionAttempt, setDetectionAttempt] = useState(0);
  const durationRequestId = useRef(0);

  // Apply the provisional period first; the detection effect below must run after it.
  useEffect(() => {
    if (!activeControl) return;
    const persisted = activeControl.tag ? persistedConfigs[activeControl.tag] : undefined;
    const duration =
      initialUrlDuration ||
      durationOverrides[activeControl.id] ||
      persisted?.duration ||
      durationFromPrompt(initialPromptFor(activeControl, persisted, activeToken)) ||
      DEFAULT_DURATION;
    setDurationOverrides((current) => ({ ...current, [activeControl.id]: duration }));
    setDurationInsight({ status: 'ready', duration, sentences: [] });
  }, [activeTokenId, persistedConfigs, initialUrlDuration]);

  useEffect(() => {
    const control = contentControls.find((item) => item.id === activeTokenId);
    if (!control) {
      setDurationInsight(NO_SELECTION);
      return;
    }
    if (control.tag && persistedConfigs[control.tag]?.duration) return;

    const requestId = ++durationRequestId.current;
    const abortController = new AbortController();
    setDurationInsight({ status: 'loading', duration: 'Detecting…', sentences: [] });

    detectDuration(control, abortController.signal)
      .then((payload) => {
        if (durationRequestId.current !== requestId) return;
        const detectedDuration = payload.duration ?? 'Not specified';
        setDurationOverrides((current) => ({ ...current, [control.id]: detectedDuration }));
        setDurationInsight({
          status: payload.analyzed ? 'ready' : 'error',
          duration: detectedDuration,
          sentences: payload.sentences ?? [],
          message: payload.message,
        });
      })
      .catch((requestError) => {
        if (abortController.signal.aborted || durationRequestId.current !== requestId) return;
        setDurationInsight({
          status: 'error',
          duration: 'Unavailable',
          sentences: [],
          message: requestError instanceof Error ? requestError.message : 'Unable to detect the reporting period',
        });
      });

    return () => abortController.abort();
  }, [activeTokenId, documentVersion, contentControls, detectionAttempt, persistedConfigs]);

  /** Replaces the period with the user's text and discards any detection still in progress. */
  const editDuration = (value: string) => {
    durationRequestId.current += 1;
    setDurationOverrides((current) => ({ ...current, [activeTokenId]: value }));
    setDurationInsight((current) => ({ ...current, status: 'ready', duration: value, message: undefined }));
  };

  /** Runs detection again, for example after the user selects a control. */
  const redetectDuration = () => setDetectionAttempt((current) => current + 1);

  return {
    durationInsight,
    effectiveDuration: durationOverrides[activeTokenId] ?? durationInsight.duration,
    editDuration,
    redetectDuration,
  };
}
