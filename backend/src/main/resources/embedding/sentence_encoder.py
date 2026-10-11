#!/usr/bin/env python3
"""Owned NDJSON CPU/GPU encoder. Requires a pinned model revision; never truncates input.

The transformer position limit (512 for MiniLM) is explicit provenance, rather than
SentenceTransformers' shorter default truncation length. Only L2-normalised FP32
mean-pooled 384-dimensional sentence-transformer models are supported here.
"""
import json
import sys
import time

import torch
from sentence_transformers import SentenceTransformer

model = None
for line in sys.stdin:
    try:
        request = json.loads(line)
        operation = request['operation']
        if operation == 'init':
            config = request['config']
            if len(config['revision']) != 40 or any(c not in '0123456789abcdef' for c in config['revision']):
                raise ValueError('An immutable 40-character model revision is required')
            backend = config['backend']
            device = config.get('device') or ('cpu' if backend == 'CPU' else 'cuda:0')
            fallback = None
            if backend == 'GPU' and not (device.startswith('cuda') and torch.cuda.is_available()):
                if not config['allowCpuFallback']:
                    raise ValueError('Requested GPU inference runtime/device is unavailable')
                device, backend, fallback = 'cpu', 'CPU', 'CUDA_INFERENCE_UNAVAILABLE'
            if backend not in ('CPU', 'GPU'):
                raise ValueError('This local provider supports CPU or CUDA inference')
            if backend == 'CPU' and device != 'cpu':
                raise ValueError('CPU inference requires the cpu device')
            torch.set_num_threads(config.get('threads', 1))
            model = SentenceTransformer(config['modelId'], revision=config['revision'], device=device,
                                        local_files_only=config.get('localOnly', True))
            maximum = model[0].auto_model.config.max_position_embeddings
            model.max_seq_length = maximum
            if model.get_sentence_embedding_dimension() != 384:
                raise ValueError('Unsupported model dimension')
            if not getattr(model[1], 'pooling_mode_mean_tokens', False):
                raise ValueError('Unsupported pooling')
            result = dict(metadata=dict(modelId=config['modelId'], revision=config['revision'], pooling='MEAN',
                                        normalization='L2', precision='FP32', backend=backend,
                                        runtime=f'torch/{torch.__version__};maxTokens={maximum}', device=device,
                                        fallbackReason=fallback), maximumTokens=maximum)
        elif operation == 'tokens':
            result = dict(tokens=len(model.tokenizer(request['text'], truncation=False)['input_ids']))
        elif operation == 'encode':
            inputs = request['inputs']
            if any(len(model.tokenizer(i['text'], truncation=False)['input_ids']) > model.max_seq_length for i in inputs):
                raise ValueError('EMBEDDING_INPUT_TOO_LONG')
            started = time.perf_counter()
            values = model.encode([i['text'] for i in inputs], batch_size=16, normalize_embeddings=True,
                                  convert_to_numpy=True, show_progress_bar=False)
            result = dict(vectors={i['hash']: v.astype('float64').tolist() for i, v in zip(inputs, values)},
                          milliseconds=1000 * (time.perf_counter() - started))
        else:
            raise ValueError('Unknown operation')
        print(json.dumps(result, allow_nan=False), flush=True)
    except Exception as error:
        print(json.dumps(dict(error=str(error))), flush=True)
