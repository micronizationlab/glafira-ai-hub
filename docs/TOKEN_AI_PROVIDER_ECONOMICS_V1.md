# GLAFIRA Token — AI Provider Token Economics (v1)

**Snapshot date:** 2026-11-20  
**Currency:** USD  
**Units:** USD per 1,000,000 tokens unless stated otherwise  
**Status:** first sourced price baseline; not a final routing decision or investment recommendation

## 1. Purpose

Build a provider-neutral cost model for GLAFIRA's AI workloads. This document separates:
- direct inference providers;
- cloud marketplaces and API gateways;
- self-hosted inference, where cost is compute, energy and operations rather than a per-token invoice.

A price snapshot is not a quality ranking. GLAFIRA must route by a measured combination of output quality, task success, latency, throughput, availability, context size, tool support, data policy and total cost.

## 2. Sourced direct-provider price snapshot

Representative text models are selected to provide an initial cost comparison. Prices are public list rates found on the providers' official pages on the snapshot date; promotional windows, region, context thresholds and service tiers can change the effective price.

| Provider | Representative model | Input / 1M | Output / 1M | Important pricing condition |
|---|---|---:|---:|---|
| OpenAI | GPT-6 Luna | $0.05 | $0.25 | Standard rate; cached input $0.005 |
| Anthropic | Claude Haiku 5.5 | $0.10 | $0.50 | Prompts up to 100K; higher context tier costs more |
| Google | Gemini 3.8 Flash | $0.75 | $3.75 | Paid Standard tier through 2026-12-31; scheduled price change 2027-01-01 |
| DeepSeek | DeepSeek V4.1 Flash | $0.30 peak / $0.15 off-peak | $1.20 peak / $0.60 off-peak | Time-of-day pricing; cache-hit input is substantially cheaper |
| Mistral | Mistral Small 4 | $0.15 | $0.60 | Cached input $0.015; batch discount may apply |
| Cohere | Command R7B | $0.0375 | $0.15 | 128K context; verify production-key terms and current model lifecycle |
| Alibaba Cloud Model Studio | Qwen 3.7 Plus | $0.276 | $1.101 | Global/EU deployment tier shown; other regions and context tiers differ |
| xAI | Grok 4.7 | $2.00 | $6.00 | Short context below 200K; long-context pricing is higher |
| Groq | OpenAI GPT-OSS 20B | $0.075 | $0.30 | Hosted inference price; other models may be contact-sales only |

### Cloud and routing channels (not independent model families)

| Channel | Pricing note | Accounting treatment |
|---|---|---|
| Amazon Bedrock | Model-specific rates; for example GPT-6 Luna global pricing is $0.10 input / $0.50 output per 1M tokens | Treat as a separate deployment route for the same underlying model, with region/tier premiums and cloud billing metadata |
| OpenRouter | Underlying model/provider token prices are passed through; Standard plan charges a 5.5% fee on credit purchases, Business 8% | Record model inference and credit-purchase platform fee separately; do not add the fee to every token request as if it were a model markup |

### Local inference

Ollama and other self-hosted runtimes do not have a vendor per-token list price. Their economic cost must be estimated from:
- GPU/CPU and RAM purchase or depreciation;
- measured energy consumption under idle and inference load;
- electricity tariff and cooling overhead;
- storage, network, maintenance and operator time;
- tokens/second, concurrency and quality for the actual model/quantization.

Do not label local inference as “free”.

## 3. Example: same workload, different list prices

Illustrative request: **100,000 input tokens + 20,000 output tokens**, no cache, no tools, no taxes, no long-context uplift.

Formula:

`cost = input_tokens / 1,000,000 * input_rate + output_tokens / 1,000,000 * output_rate`

| Model | Approx. request cost |
|---|---:|
| Claude Haiku 5.5 (≤100K tier) | $0.02000 |
| GPT-6 Luna | $0.01000 |
| Gemini 3.8 Flash (2026 rate) | $0.15000 |
| DeepSeek V4.1 Flash (peak) | $0.05400 |
| DeepSeek V4.1 Flash (off-peak) | $0.02700 |
| Mistral Small 4 | $0.02700 |
| Cohere Command R7B | $0.00675 |
| Qwen 3.7 Plus | $0.04962 |
| Grok 4.7 | $0.32000 |
| Groq GPT-OSS 20B | $0.01350 |

This table is a cost illustration only. It does not imply that the models are interchangeable or have equal task success.

## 4. GLAFIRA accounting model

Every inference event should persist at least:

- `provider_id`, `deployment_route`, `model_id`, immutable model/version identifier where available;
- input, output, cached-input, reasoning and modality token counts as separate dimensions;
- request count, retry count, tool calls and non-token charges;
- region, service tier, batch/priority mode and pricing timestamp;
- provider-reported usage and provider-reported cost, when available;
- normalized estimated cost in USD and the original billing currency;
- task/project/agent attribution without storing secrets in the ledger.

Do not infer the bill from total tokens alone. Cache reads/writes, long-context tiers, audio/image/video units, grounding, tools, failed attempts and regional premiums can materially change it.

## 5. Provider routing score

For each workload class, benchmark a candidate model/provider route using:

1. task success and quality against a fixed evaluation set;
2. cost per successful completed task (not merely cost per request);
3. p50/p95 time-to-first-token and end-to-end latency;
4. output throughput and sustainable concurrency;
5. availability, timeout, 429 and 5xx rates;
6. context limits, structured output and tool-call correctness;
7. privacy, retention, training-use and data-residency terms;
8. rate limits, billing minimums and account/region availability.

Recommended workload classes:
- lightweight classification and routing;
- everyday dialogue and short summaries;
- coding and repository changes;
- long-document analysis and research;
- voice and multimodal work;
- high-risk operations requiring deterministic tools and owner approval.

Use a quality/latency/cost frontier rather than selecting the cheapest provider globally. A low-cost model that needs repeated retries or fails to complete a task can be more expensive.

## 6. Energy and token economics — next research slice

A future GLAFIRA energy-token model must not equate tokens with energy. Token counts are an application-level billing unit; electricity use depends on model architecture, hardware, batch size, quantization, utilization, memory movement, cooling and data-centre overhead.

For a defensible energy estimate, collect measured wall power and runtime for a fixed benchmark on each hardware class, then calculate:

`energy_kWh = average_power_W * runtime_seconds / 3,600,000`

Record idle baseline separately and subtract it only when the measurement protocol supports doing so. Report uncertainty and hardware/model details. Do not mint rewards from unverified self-reported token counts or claimed energy savings.

The next slice should compare:
- metered GPU/CPU energy and workload attribution;
- renewable-energy certificates and carbon accounting boundaries;
- compute contribution proofs and anti-Sybil controls;
- token issuance, reserve, redemption and transfer rules;
- legal, tax, custody and consumer-protection constraints in target jurisdictions.

No financial token issuance or transfer mechanism is authorized by this research document.

## 7. Official source register

All links checked for this v1 snapshot on 2026-11-20. Recheck before production routing or any economic decision.

- OpenAI API pricing: https://developers.openai.com/api/docs/pricing
- Anthropic pricing: https://claude.com/pricing
- Google Gemini API pricing: https://ai.google.dev/gemini-api/docs/pricing
- DeepSeek API pricing: https://api-docs.deepseek.com/quick_start/pricing/
- Mistral API pricing: https://docs.mistral.ai/inference/pricing?currency=usd
- Cohere Command R7B model/pricing: https://docs.cohere.com/docs/command-r7b
- Alibaba Cloud Model Studio pricing: https://www.alibabacloud.com/help/en/model-studio/model-pricing
- xAI API pricing: https://docs.x.ai/developers/pricing
- Groq supported models and prices: https://console.groq.com/docs/models
- Amazon Bedrock model pricing example: https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-openai-gpt-6-luna.html
- OpenRouter pricing and platform fee: https://openrouter.ai/pricing

## 8. Limitations

- This is a sourced baseline, not an exhaustive provider/model catalogue.
- Prices are list rates and may change without notice; promotional dates and tier thresholds are called out only where visible on the source page.
- Some providers publish different rates by region, context, tier or time window. The exact endpoint and deployment must be captured.
- Quality and throughput comparisons require reproducible GLAFIRA-specific benchmarks; price pages alone cannot establish them.
- Cryptocurrency/energy-token design remains a separate research phase and requires legal and security review before implementation.
