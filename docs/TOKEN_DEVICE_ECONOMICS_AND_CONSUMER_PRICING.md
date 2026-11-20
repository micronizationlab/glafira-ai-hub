# Project «Токен» — device economics, GLF pricing and free-service policy

**Version:** 1.0-draft | **Snapshot:** 2026-11-20  
**Status:** spreadsheet-ready specification; values remain illustrative until measured  
**Related:** #107, #114–#117, #152, #154; Desktop economy spec §§11, 14–17.

## 1. Purpose and units

For every exact device model, answer: useful accepted work per day; total network cost; fair GLF reward; and whether the owner's earnings can cover VPN, AI or both.

Keep units separate: GCU measures resources; GLF settles service value; provider tokens are provider billing units; RUB reference is not guaranteed redemption or a public exchange rate. Reward is based on accepted deliverables, not FLOPS, uptime or theoretical tokens/sec.

## 2. Benchmark table

Use TOKEN_DEVICE_BENCHMARK_TEMPLATE.csv. Add one row per exact physical device + workload + model/quantization. Record vendor/SKU/year, CPU/GPU/NPU, RAM/VRAM, OS, model/quantization, benchmark version, throughput, p50/p95 latency, quality/acceptance, retries, opted-in hours, idle availability, incremental watts, electricity tariff, bandwidth/storage, purchase/residual value, useful months, failure rate, thermal/owner-contention/security factors, measurement confidence and timestamp.

Benchmark CPU-only and GPU separately. Include laptops, discrete GPU desktops, integrated GPU/NPU, workstations, mini-PCs and selected Android phones. Never rank devices by marketing TOPS alone.

## 3. Formula

available_hours = opted_in_hours × idle_availability_factor × thermal_factor × owner_contention_factor × connectivity_factor

candidate_work_j = measured_throughput_j × available_hours

accepted_work_j = candidate_work_j × acceptance_rate_j × (1 - retry_loss_j)

energy_kWh = max(0, incremental_power_W) / 1000 × contribution_hours

electricity_RUB = energy_kWh × electricity_tariff_RUB_per_kWh

direct_cost_RUB = electricity_RUB + bandwidth_RUB + incremental_storage_RUB + other_metered_cost_RUB

depreciation_per_hour = max(0, purchase_price_RUB - residual_value_RUB) / (useful_months × expected_contribution_hours_per_month)

depreciation_RUB = depreciation_per_hour × contribution_hours

risk_reserve_RUB = accepted_work_value_RUB × (failure_rate + dispute_rate + support_rate) × risk_multiplier

accepted_work_value_RUB = Σ(accepted_units_j × benchmark_value_per_accepted_unit_j)

Use the lower of verified external cost for an equivalent accepted deliverable and buyer's authorized maximum budget. If no comparable benchmark exists, block automatic reward quotes.

net_value_RUB = accepted_work_value_RUB - direct_cost_RUB - depreciation_RUB - risk_reserve_RUB - platform_operating_cost_RUB

reward_pool_RUB = max(0, min(net_value_RUB × contributor_share, net_value_RUB))

GLF_reward = floor(reward_pool_RUB / GLF_reference_value_RUB)

platform_margin_RUB = net_value_RUB - reward_pool_RUB

Zero/negative net value means no reward and scheduler should avoid that device/workload. Owner policy versions contributor share, caps and reference value. Show measured vs estimated values distinctly.

Customer break-even:
- GLF_value_earned_RUB = GLF_earned × GLF_reference_value_RUB
- VPN_daily_cost_GLF = monthly_VPN_package_GLF / 30
- AI_daily_cost_GLF = measured_AI_usage_GLF or separately disclosed active-day allowance
- days_to_cover_VPN = monthly_VPN_package_GLF / average_daily_GLF_reward
- days_to_cover_VPN_plus_AI = (monthly_VPN_package_GLF + monthly_AI_package_GLF) / average_daily_GLF_reward
- hours_to_cover_daily_services = (VPN_daily_cost_GLF + AI_daily_cost_GLF) / net_GLF_reward_per_contribution_hour

Show conservative/base/optimistic ranges. Do not say a device pays off service unless conservative case is positive.

## 4. Device classes

| Class | Rule | Scheduling |
|---|---|---|
| A — attractive | Conservative net positive, stable throughput | Prioritize compatible tasks |
| B — conditional | Positive only for selected workloads/idle/off-peak windows | Restrict to matching jobs |
| C — marginal | Near break-even after energy/depreciation | Do not recruit actively |
| D — uneconomic | Negative net or security/thermal failure | No paid work |
| E — unmeasured | No reliable benchmark | Benchmark first; no quote |

Classify per device + workload + model, not by brand alone.

## 5. Contribution safety

Windows Desktop/Agent yields to owner activity. Android is opt-in, Wi-Fi only, charging/healthy battery/safe temperature, never above the existing 10% cap and never cellular. macOS Desktop is coordinator only; Mac worker requires separate approval. iOS remains client-first. Never use personal files, credentials or memory as task input without explicit scope. Tasks are signed, typed, sandboxed and audited.

## 6. GLF reference and daily billing

Illustration: 300 RUB / 30 days represented by 10,000 GLF gives 0.03 RUB/GLF display reference and 333.33 GLF nominal daily allocation. This is not a cash redemption promise.

Credit only after verified payment. VPN daily debit occurs only on verified active VPN use. No debit if client offline, VPN unused or our outage prevented service. AI is metered by successful usage or a separately disclosed active-day plan. Do not debit whole month upfront and then daily again. Define expiry, pause, rollover, refunds, outage compensation and timezone rules before launch.

## 7. Provider free quota vs GLAFIRA included allowance

Upstream provider quota and GLAFIRA's subsidized allowance are separate. GLAFIRA cap must not exceed remaining provider quota or approved cost budget.

| Provider | Free-access signal | Caveat / control |
|---|---|---|
| Google Gemini API — Gemini 3.8 Flash / 3.1 Flash-Lite | Official pricing page shows free input/output tier for selected models | Exact RPM/TPM/RPD/TPD are project+model+region specific; fetch quota from API and console. Free tier data may be used to improve Google products. Not an unlimited shared pool |
| GroqCloud — GPT-OSS 120B / 20B | Official table example: 30 RPM, 1,000 RPD, 8K TPM, 200K TPD | Treat as an organization-level rate ceiling, not a per-user entitlement; verify current model/account page |
| Mistral La Plateforme — selected models | Free experimentation tier exists | No universal public token amount; read current RPS/TPM/month limits from account console and commercial terms |
| Cohere Trial — Chat / Embed | Trial key: Chat example 20 calls/min, Embed 5 calls/min; other endpoints 1,000 calls/month | Endpoint-specific, not production service; production key is metered |
| OpenRouter free model variants (:free) | 20 requests/minute and 50 requests/day; after buying $10 credits the free-model cap can rise to 1,000/day | Failed attempts count; model availability/provider limits vary; this is a paid-credit condition for the higher cap |
| Alibaba Cloud Model Studio — eligible Qwen/other model IDs | New-account free quota is typically 1M tokens per eligible model, valid 90 days; per-model eligibility is shown in console | Not every model/region qualifies. On 2026-11-15 free quotas were removed for DeepSeek V4.1 Flash-1017, Qwen3.8-Max-0902, DeepSeek V4-Pro and Kimi-K3. Enable Free-Quota-Only to prevent automatic paid fallback |
| GigaChat API — Lite / Pro / Max / Ultra | Published Freemium for eligible existing individual customers: 250M / 40M / 25M / 50M tokens respectively, 12 months; one generation stream | Important transition: new customers or customers who had not bought a package from 2026-09-01 are directed to cloud.ru tariffs. Do not assume every new GLAFIRA customer receives the historical Freemium grant |
| OpenAI API | API is metered by default; eligible ChatGPT Pro/Business subscribers may separately claim monthly API credits: Pro $100/$200/$500; Business $20 per Standard seat and $100 per Premium seat, workspace cap $500 | Claim required, 7-day eligibility and linked API organization; credits expire at cycle end. ChatGPT app Free/Plus/Pro allowance is not an API token quota |
| Anthropic API | General Console API is separately billed; approved External Researcher Access applications for eligible AI safety research may receive $1,000 API credits | Research-only program, application/approval required; Claude.ai Free/Pro app access is not API credits. Claude Pro does not include Console API usage |
| DeepSeek API | API inference billed against account balance/granted credits; consumer DeepSeek app free use is a different product | Do not infer API free access from app. Free Files API or search/fetch promotions are separate from model inference |
| xAI API | Prepaid API credits / monthly invoiced billing; no general free model-token quota assumed | Grok consumer subscription and xAI API credits are separate |
| Yandex Cloud AI Studio / YandexGPT | No universal per-model free API token quota confirmed for new customers; cloud trial grants are billing-account/service dependent and currently unavailable to new clients in the trial flow | Do not use old promo pages as current quota; inspect live billing console and current regional terms |

Store official source, checked_at, account/project, exact model ID, region, free token/request amount, RPM/RPD/TPM/TPD/monthly spend, reset timezone, expiry, eligibility, commercial-use/data terms and effective dates. Consumer chat subscriptions, API trial grants and API free quotas are three different products; never pool them together. Before exposing a provider free tier to customers, verify that the provider terms permit multi-user/commercial use and that free-tier data handling is appropriate. Stale/unknown quota blocks automatic free routing. On exhaustion, stop, ask to spend GLF, or route to local model; no silent paid fallback.

## 8. Telegram shop

Five catalogue items: free download; VPN plan; AI plan/GLF allowance; combined VPN+AI; GLF contribution/donation info (not investment).

AI/VPN sold inside Telegram are digital services. Current Telegram Developer Terms require in-app digital purchases to use Stars (XTR), not direct RUB or TON/crypto. Stars are not GLF or a general wallet transfer rail. Verify Stars server-side, then grant service/GLF through our separate ledger. External website checkout is separate and needs platform/store/legal review. Bot must support idempotent invoices, signed update validation, reconciliation, refunds, /paysupport and audit.

Official sources: https://core.telegram.org/bots/payments-stars ; https://telegram.org/tos/bot-developers ; https://telegram.org/tos/stars ; https://core.telegram.org/bots/api#sendinvoice

## 9. Decisions requiring owner approval

GLF reference value; contributor share/caps/issuance budget; package expiry/rollover/inactive-day pause; free local/hosted AI allowances; per-model AI caps; VPN active-day/outage rules; Stars prices/net revenue; legal disclosures. GLF is not a security, investment, yield or guaranteed cash-equivalent.

## 10. Sources checked 2026-11-20

Gemini: https://ai.google.dev/gemini-api/docs/pricing  
Groq: https://console.groq.com/docs/rate-limits  
Cohere: https://docs.cohere.com/v2/docs/cohere-faqs  
OpenAI: https://platform.openai.com/pricing/  
DeepSeek: https://api-docs.deepseek.com/quick_start/pricing/  
xAI: https://docs.x.ai/console/billing  
Telegram: https://core.telegram.org/bots/payments-stars and https://telegram.org/tos/bot-developers

Refresh official pages and actual account console before changing any allowance or price.
