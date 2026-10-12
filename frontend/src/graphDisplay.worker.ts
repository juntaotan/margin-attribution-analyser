import { GraphDisplayIndex, type DisplayInput, type DisplayQuery } from './graphDisplay';

let index: GraphDisplayIndex | null = null;
/** Own the complete graph index; only bounded projections return to the UI. */
self.onmessage = (event: MessageEvent<{ sequence: number; input?: DisplayInput; query: DisplayQuery }>) => {
  const { sequence, input, query } = event.data;
  try {
    if (input) index = new GraphDisplayIndex(input);
    if (!index) throw new Error('Graph display index is unavailable.');
    self.postMessage({ sequence, result: index.query(query) });
  } catch (error) {
    self.postMessage({ sequence, error: error instanceof Error ? error.message : 'Graph preparation failed.' });
  }
};
