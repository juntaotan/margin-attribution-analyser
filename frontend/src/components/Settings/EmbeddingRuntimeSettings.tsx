import React, { useCallback, useEffect, useState } from 'react';

interface EmbeddingStatus {
  state: 'NOT_INSTALLED' | 'INSTALLING' | 'READY' | 'FAILED';
  variant: 'cpu' | 'cuda' | null;
  stage: string | null;
  message: string | null;
  modelId: string;
  revision: string;
  cudaAvailable: boolean | null;
}

const ENDPOINT = '/api/v1/settings/embedding';
const SOURCES: Record<'cpu' | 'cuda', string> = {
  cpu: 'download.pytorch.org (PyTorch CPU), pypi.org (sentence-transformers) and huggingface.co (model); about 1.5 GB on disk',
  cuda: 'pypi.org (PyTorch with CUDA 13 libraries and sentence-transformers) and huggingface.co (model); about 6 GB on disk',
};

/** Remember the inference backend matching the installed runtime for subsequent analysis requests. */
const rememberBackend = (status: EmbeddingStatus) => {
  try {
    localStorage.setItem(
      'margintrace.embedding',
      JSON.stringify({ backend: status.variant === 'cuda' && status.cudaAvailable ? 'GPU' : 'CPU' }),
    );
  } catch {
    /* The backend defaults to CPU inference. */
  }
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
      const next = (await response.json()) as EmbeddingStatus;
      if (next.state === 'READY') rememberBackend(next);
      setStatus(next);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Embedding status unavailable');
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);
  useEffect(() => {
    if (status?.state !== 'INSTALLING') return;
    const timer = window.setInterval(() => void load(), 3000);
    return () => window.clearInterval(timer);
  }, [status?.state, load]);
  const install = async () => {
    if (!window.confirm(`The backend server will download from ${SOURCES[variant]}. Continue?`)) return;
    const response = await fetch(`${ENDPOINT}/install`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ variant }),
    });
    if (!response.ok) {
      const body = (await response.json().catch(() => ({}))) as { message?: string };
      setError(body.message || `Install failed (${response.status})`);
      return;
    }
    setStatus((await response.json()) as EmbeddingStatus);
    setError(null);
  };
  const summary = !status
    ? 'Loading'
    : status.state === 'READY'
      ? `Ready · ${status.variant === 'cuda' ? `CUDA${status.cudaAvailable ? '' : ' (GPU unavailable, CPU inference)'}` : 'CPU'}`
      : status.state === 'INSTALLING'
        ? `Installing · ${status.stage}`
        : status.state === 'FAILED'
          ? 'Failed'
          : 'Not installed';
  const gpuMissing = status?.state === 'READY' && status.variant === 'cuda' && !status.cudaAvailable;
  return (
    <section className="border-t pt-4 space-y-3">
      <h3 className="font-semibold">
        Embedding model <span className="ml-2 font-normal text-slate-600">{summary}</span>
      </h3>
      {status && (
        <p>
          Model: {status.modelId} · revision {status.revision}
        </p>
      )}
      {status?.state === 'FAILED' && (
        <>
          <p className="text-[#A64242]">Failed during: {status.stage}</p>
          <pre className="whitespace-pre-wrap break-all bg-slate-50 p-2">{status.message}</pre>
        </>
      )}
      {status?.state === 'NOT_INSTALLED' && status.message && <p>{status.message}</p>}
      <label className="block">
        Inference runtime{' '}
        <select
          aria-label="Inference runtime"
          className="border rounded ml-2 p-1"
          value={variant}
          disabled={status?.state === 'INSTALLING'}
          onChange={(e) => setVariant(e.target.value as 'cpu' | 'cuda')}
        >
          <option value="cpu">CPU</option>
          <option value="cuda">NVIDIA CUDA (requires the CUDA backend image)</option>
        </select>
      </label>
      <button
        className="border rounded px-3 py-2"
        disabled={!status || status.state === 'INSTALLING'}
        onClick={() => void install()}
      >
        {status?.state === 'READY' ? 'Reinstall' : 'Download and install'}
      </button>
      {error && (
        <p className="text-[#A64242]" role="alert">
          {error}
        </p>
      )}
      {(variant === 'cuda' || gpuMissing) && (
        <p className={gpuMissing ? 'text-[#A64242]' : 'text-slate-600'} role={gpuMissing ? 'alert' : undefined}>
          {gpuMissing ? 'No CUDA device was detected, so inference runs on the CPU. ' : ''}GPU inference uses CUDA 13
          libraries and needs an NVIDIA driver that supports CUDA 13 (about version 580 or later) on the backend host;
          check the CUDA Version shown by nvidia-smi. Ubuntu: sudo ubuntu-drivers install, then reboot. Windows/WSL2:
          update the Windows NVIDIA driver, not inside WSL, then restart Docker Desktop. Reinstall afterwards.
        </p>
      )}
      <p className="text-slate-500">
        Note: installed on the backend server; reinstalling replaces the current runtime.
      </p>
    </section>
  );
};
