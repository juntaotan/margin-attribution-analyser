# Local AI analysis report

The right-hand **Analysis report summary** is calculated for the selected material and the two periods shown in the analysis. Java determines the category and evidence first; llama.cpp only writes a short explanation from those facts. The UI still shows the category and evidence when the model is offline.

## Start llama.cpp

Run a chat-capable GGUF model with `llama-server` on port 8081:

```bash
llama-server --model /path/to/chat-model.gguf --alias local --host 127.0.0.1 --port 8081 --ctx-size 4096
```

For a backend running in Docker, bind `llama-server` to `0.0.0.0` instead. The Compose files route the backend to `http://host.docker.internal:8081`. Set `LLAMA_BASE_URL` if the server is elsewhere and `LLAMA_MODEL` if its advertised model ID differs from `local`. No API key or LangChain dependency is required; the backend calls llama-server's `/v1/chat/completions` endpoint directly.

An existing llama-server started with `--embeddings` is not a chat server. Keep it for embeddings and start a second llama-server with a chat model on port 8081, without `--embeddings` or `--reranking`. The report uses chat completions.

The current sample periods have equal quantities and topology but different material cost. They should be classified as **单位成本变化**. This is a fourth outcome in addition to overuse, suspected replacement, and BOM change. The data do not support calling the sample an overuse or a replacement.

## Classification boundaries

- **BOM 变化** requires different BOM material lines for the production orders in the two periods. A changed BOM number without both line sets is only evidence to investigate.
- **疑似物料更换** requires both removed and added consumed materials under the same product, without a confirmed BOM line change. The available records cannot prove that the materials are approved substitutes.
- **用料超出 BOM 计划** requires actual material quantity per unit of product to exceed the current BOM unit usage. **单位产出用料增加** compares the periods when a BOM plan is unavailable.
- **单位成本变化** reports a changed cost per unit when no stronger structural or usage signal applies.

The endpoint is `POST /api/v1/analysis/root-cause-report` with `actualStartDate`, `actualEndDate`, `comparableStartDate`, `comparableEndDate`, and `inventoryId`. The backend recomputes the graphs from warehouse records, so the model does not receive client-supplied financial facts.
