import React, { useCallback, useEffect, useState } from 'react';

interface EmbeddingStatus {
  state: 'NOT_INSTALLED' | 'INSTALLING' | 'READY' | 'FAILED';
  variant: 'cpu' | 'cuda' | null; stage: string | null; message: string | null;
  modelId: string; revision: string; cudaAvailable: boolean | null;
}

const ENDPOINT = '/api/v1/settings/embedding';
const SOURCES: Record<'cpu' | 'cuda', string> = {
  cpu: 'download.pytorch.org (PyTorch CPU), pypi.org (sentence-transformers) and huggingface.co (model); about 1.5 GB on disk',
  cuda: 'pypi.org (PyTorch with CUDA 13 libraries and sentence-transformers) and huggingface.co (model); about 6 GB on disk',
};

/** Remember the inference backend matching the installed runtime for subsequent analysis requests. */
const rememberBackend = (status: EmbeddingStatus) => {
  try { localStorage.setItem('margintrace.embedding', JSON.stringify({ backend: status.variant === 'cuda' && status.cudaAvailable ? 'GPU' : 'CPU' })); }
  catch { /* The backend defaults to CPU inference. */ }
};

/** Installs the backend's local embedding runtime and model after explicit consent. */
export const EmbeddingRuntimeSettings: React.FC = () => {
  const [status, setStatus] = useState<EmbeddingStatus | null>(null);
  const [variant, setVariant] = useState<'cpu' | 'cuda'>('cpu');
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(async () => {
    try {
      const response = await fetch(ENDPOINT);
      if (!response.ok) throw new Error(`Embedding status unavailable (${response.status})`);
      const next = await response.json() as EmbeddingStatus;
      if (next.state === 'READY') rememberBackend(next);
      setStatus(next); setError(null);
    } catch (err) { setError(err instanceof Error ? err.message : 'Embedding status unavailable'); }
  }, []);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (status?.state !== 'INSTALLING') return;
    const timer = window.setInterval(() => void load(), 3000);
    return () => window.clearInterval(timer);
  }, [status?.state, load]);
  const install = async () => {
    if (!window.confirm(`The backend server will download from ${SOURCES[variant]}. Continue?`)) return;
    const response = await fetch(`${ENDPOINT}/install`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ variant }) });
    if (!response.ok) { const body = await response.json().catch(() => ({})) as { message?: string }; setError(body.message || `Install failed (${response.status})`); return; }
    setStatus(await response.json() as EmbeddingStatus); setError(null);
  };
  const summary = !status ? 'Loading' : status.state === 'READY' ? `Ready · ${status.variant === 'cuda' ? `CUDA${status.cudaAvailable ? '' : ' (GPU unavailable, CPU inference)'}` : 'CPU'}`
    : status.state === 'INSTALLING' ? `Installing · ${status.stage}` : status.state === 'FAILED' ? 'Failed' : 'Not installed';
  return <details className="border rounded-lg bg-white settings-section"><summary className="cursor-pointer p-5 text-sm font-semibold">Embedding model <span className="ml-2 text-xs font-normal text-slate-600">{summary}</span></summary>
    <div className="p-5 pt-0 space-y-3 text-xs settings-content">
      {status && <p>Model: {status.modelId} · revision {status.revision}</p>}
      {status?.state === 'FAILED' && <><p className="text-[#A64242]">Failed during: {status.stage}</p><pre className="whitespace-pre-wrap break-all bg-slate-50 p-2">{status.message}</pre></>}
      {status?.state === 'NOT_INSTALLED' && status.message && <p>{status.message}</p>}
      <label className="block">Inference runtime <select aria-label="Inference runtime" className="border rounded ml-2 p-1" value={variant} disabled={status?.state === 'INSTALLING'} onChange={e => setVariant(e.target.value as 'cpu' | 'cuda')}>
        <option value="cpu">CPU</option><option value="cuda">NVIDIA CUDA (requires the CUDA backend image)</option>
      </select></label>
      <button className="border rounded px-3 py-2" disabled={!status || status.state === 'INSTALLING'} onClick={() => void install()}>{status?.state === 'READY' ? 'Reinstall' : 'Download and install'}</button>
      {error && <p className="text-[#A64242]" role="alert">{error}</p>}
      <p className="text-slate-600">Files are installed on the backend server. Reinstalling replaces the existing runtime. Other 384-dimensional mean-pooling models are configured with EMBEDDING_MODEL and EMBEDDING_REVISION.</p>
    </div>
  </details>;
};
