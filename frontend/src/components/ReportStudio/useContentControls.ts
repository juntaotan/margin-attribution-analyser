import { useCallback, useEffect, useState } from 'react';
import { fetchContentControls, syncPlaceholderConfigs } from './reportStudioApi';
import { DocumentContentControl, PlaceholderConfig } from './types';

async function loadPersistedConfigs(controls: DocumentContentControl[]) {
  try {
    return await syncPlaceholderConfigs(controls);
  } catch (err) {
    console.warn('Failed to sync persisted placeholder configs:', err);
    return undefined;
  }
}

/**
 * Loads the content controls of the saved report document, their persisted configurations, and tracks the
 * selected control.
 *
 * The scan runs on mount and again whenever `loadContentControls` is called. After each scan the selection is
 * kept if the control still exists, and otherwise moves to the first control. A failed configuration sync is
 * logged and leaves the previous configurations in place; a failed scan is reported in `controlsError`.
 */
export function useContentControls() {
  const [contentControls, setContentControls] = useState<DocumentContentControl[]>([]);
  const [controlsLoading, setControlsLoading] = useState(true);
  const [controlsError, setControlsError] = useState<string>();
  const [documentVersion, setDocumentVersion] = useState<number>();
  const [persistedConfigs, setPersistedConfigs] = useState<Record<string, PlaceholderConfig>>({});
  const [activeTokenId, setActiveTokenId] = useState('');

  const loadContentControls = useCallback(async () => {
    setControlsLoading(true);
    setControlsError(undefined);
    try {
      const { controls, documentVersion } = await fetchContentControls();
      setContentControls(controls);
      setDocumentVersion(documentVersion);
      const configs = await loadPersistedConfigs(controls);
      if (configs) setPersistedConfigs(configs);
      setActiveTokenId((currentId) =>
        controls.some((control) => control.id === currentId) ? currentId : (controls[0]?.id ?? ''),
      );
    } catch (requestError) {
      setControlsError(requestError instanceof Error ? requestError.message : 'Unable to scan document placeholders');
    } finally {
      setControlsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadContentControls();
  }, [loadContentControls]);

  return {
    contentControls,
    controlsLoading,
    controlsError,
    documentVersion,
    persistedConfigs,
    setPersistedConfigs,
    activeTokenId,
    setActiveTokenId,
    loadContentControls,
  };
}
