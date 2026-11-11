import { GraphDisplayIndex, type DisplayInput, type DisplayQuery } from './graphDisplay';

let index: GraphDisplayIndex | null = null;
let failure: string | null = null;
/**
 * Own the complete graph index; only bounded projections return to the UI. A message with `input` rebuilds the
 * index, and a message with `query` is answered with its `sequence`. An indexing failure is reported to later queries.
 */
self.onmessage = (event: MessageEvent<{ sequence?: number; input?: DisplayInput; query?: DisplayQuery }>) => {
  const { sequence, input, query } = event.data;
  if (input)
    try {
      index = new GraphDisplayIndex(input);
      failure = null;
    } catch (error) {
      index = null;
      failure = error instanceof Error ? error.message : 'Graph preparation failed.';
    }
  if (!query) return;
  try {
    if (!index) throw new Error(failure ?? 'Graph display index is unavailable.');
    self.postMessage({ sequence, result: index.query(query) });
  } catch (error) {
    self.postMessage({ sequence, error: error instanceof Error ? error.message : 'Graph preparation failed.' });
  }
};
