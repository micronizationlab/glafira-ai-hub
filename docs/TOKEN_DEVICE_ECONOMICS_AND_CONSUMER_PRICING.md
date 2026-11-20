# Project «Токен» — device economics, GLF pricing and free-service policy

**Version:** 1.0-draft | **Snapshot:** 2026-11-20  
**Status:** spreadsheet-ready specification; values remain illustrative until measured  
**Related:** #107, #114–#117, #152, #154; Desktop economy spec §§11, 14–17.

## 1. Purpose and units

For every exact device model, answer: useful accepted work per day; total network cost; fair GLF reward; and whether the owner's earnings can cover VPN, AI or both.

Keep units separate:
- GCU = normalized measurement of resources consumed by completed work.
- GLF = internal settlement/service-credit unit.
- Provider tokens = provider billing units, never GLF.
- RUB reference = accounting/display comparison, not guaranteed cash redemption or a public exchange rate.

Reward is not based on raw FLOPS, advertised TOPS, uptime or theoretical tokens/second. Compare equivalent accepted deliverables at equal quality, permissions and latency.

## 2. Device benchmark input table

Use TOKEN_DEVICE_BENCHMARK_TEMPLATE.csv. Add one row per physical device + workload + model/quantization. Do not mix CPU-only and GPU measurements.

| Input | Meaning |
|---|---|
| Vendor, exact model, year | Exact laptop/desktop/phone SKU |
| CPU, GPU, NPU | Chip and accelerator |
| RAM, VRAM | Installed and usable memory |
| Model, quantization | Exact local model manifest |
| Workload profile | Coding, text inference, embedding, transcription, image, generic task |
| Throughput | Accepted tokens/sec, tasks/hour or workload units/hour |
| p50/p95 latency | Repeated measured response time |
| Accuracy/acceptance | Same evaluation rubric across devices |
| Available hours/day | Owner opt-in window |
| Idle availability | Fraction not needed by owner |
| Incremental power W | Load minus idle baseline, measured or estimated |
| Electricity tariff | RUB/kWh |
| Bandwidth/storage | Incremental usage and price |
| Purchase/residual value | Depreciation inputs |
| Useful months | Conservative hardware horizon |
| Failure/retry rate | Rejected work and rework |
| Thermal/charge/OS factor | Safe runtime throttle |
| Security factor | Sandbox/attestation eligibility |
| Confidence | measured / estimated / unknown |

Catalogue real exact models: CPU-only laptops, integrated GPU/NPU devices, discrete GPU desktops, workstations, mini-PCs and selected Android phones. Never rank vendors from marketing TOPS alone.

## 3. Formula

All prices must share currency, tax basis and effective date. Use incremental power over idle.

### 3.1 Available hours

available_hours = opted_in_hours × idle_availability_factor × thermal_factor × owner_contention_factor × connectivity_factor

Every factor is in [0,1]. Paused, hot, busy, battery-constrained or disconnected devices receive less or no work.

### 3.2 Accepted work

candidate_work_j = measured_throughput_j × available_hours

accepted_work_j = candidate_work_j × acceptance_rate_j × (1 - retry_loss_j)

Tokens/second only estimate capacity. They do not themselves establish accepted value or reward.

### 3.3 Energy and direct costs

energy_kWh = max(0, incremental_power_W) / 1000 × contribution_hours

electricity_RUB = energy_kWh × electricity_tariff_RUB_per_kWh

direct_cost_RUB = electricity_RUB + bandwidth_RUB + incremental_storage_RUB + other_metered_cost_RUB

Mark estimated power as estimated and apply an uncertainty reserve. Do not present estimates as meter readings.

### 3.4 Depreciation and risk

depreciation_per_hour = max(0, purchase_price_RUB - residual_value_RUB) / (useful_months × expected_contribution_hours_per_month)

depreciation_RUB = depreciation_per_hour × contribution_hours

risk_reserve_RUB = accepted_work_value_RUB × (failure_rate + dispute_rate + support_rate) × risk_multiplier

All factors are versioned policy parameters.

### 3.5 Value of accepted work

accepted_work_value_RUB = Σ(accepted_units_j × benchmark_value_per_accepted_unit_j)

Use the lower of verified external cost for an equivalent accepted deliverable and the buyer's authorized maximum budget. If there is no comparable benchmark, value is unknown and automatic reward quotes are blocked. Never infer value from FLOPS, uptime or raw token count.

### 3.6 Net value and reward

net_value_RUB = accepted_work_value_RUB - direct_cost_RUB - depreciation_RUB - risk_reserve_RUB - platform_operating_cost_RUB

reward_pool_RUB = max(0, min(net_value_RUB × contributor_share, net_value_RUB))

GLF_reward = floor(reward_pool_RUB / GLF_reference_value_RUB)

platform_margin_RUB = net_value_RUB - reward_pool_RUB

Owner policy sets contributor share, caps and GLF reference value with version/effective date. Zero or negative net value means no automatic reward; scheduler should avoid assigning that workload. Reward may be held until task acceptance/dispute resolution.

### 3.7 Break-even for the customer

GLF_value_earned_RUB = GLF_earned × GLF_reference_value_RUB

VPN_daily_cost_GLF = monthly_VPN_package_GLF / 30

AI_daily_cost_GLF = measured_AI_usage_GLF (or separately disclosed active-day allowance)

days_to_cover_VPN = monthly_VPN_package_GLF / average_daily_GLF_reward

days_to_cover_VPN_plus_AI = (monthly_VPN_package_GLF + monthly_AI_package_GLF) / average_daily_GLF_reward

hours_to_cover_daily_services = (VPN_daily_cost_GLF + AI_daily_cost_GLF) / net_GLF_reward_per_contribution_hour

Show conservative/base/optimistic ranges using measured variance and uptime. Do not say a device pays off a subscription unless the conservative case is positive.

## 4. Device classification

| Class | Rule | Scheduling |
|---|---|---|
| A — attractive | Conservative net value positive, stable throughput and availability | Prioritize compatible tasks |
| B — conditional | Positive only for selected workloads/off-peak/idle/charging windows | Restrict to matching jobs |
| C — marginal | Near break-even after energy/depreciation | Do not recruit actively |
| D — uneconomic | Negative net value or fails security/thermal requirements | No paid network work |
| E — unmeasured | No reliable benchmark/cost data | Benchmark first; no automatic quote |

Classification is per device + workload + model, not permanent per brand.

## 5. Initial safety policy

- Windows Desktop/Agent: owner and user workloads preempt contribution. Measure exact hardware, RAM/VRAM, model, quantization, throughput, incremental power and accepted quality.
- Android: opt-in, Wi-Fi only, charging/healthy battery/safe temperature; no more than the existing 10% mobile contribution cap; never cellular.
- macOS Desktop is a coordinator; a Mac compute worker is not implied until separately approved.
- iOS is client-first; do not promise background compute.
- Never use personal files, browser sessions, credentials or private memory as task input unless explicitly selected and scoped.
- All work is typed, signed, sandboxed, resource-limited and audited.

## 6. GLF reference and daily subscription billing

Illustration only: 300 RUB / 30 days represented by 10,000 GLF gives 0.03 RUB/GLF as a display reference and 333.33 GLF nominal daily allocation.

Credit only after verified payment. VPN daily debit occurs only on a verified active VPN day. No debit if client offline, VPN unused, or our outage prevented service. AI is metered by successful usage or a separately disclosed active-day package. Do not debit full month upfront and then debit daily again. Define expiry, pause, rollover, refunds, outage compensation and timezone rules before launch. If inactive days pause billing, state that the package can extend beyond 30 calendar days.

## 7. Provider free quota vs GLAFIRA free allowance

These are separate:
- upstream free quota = what the provider actually allows this account/project/model;
- GLAFIRA included allowance = the smaller of remaining upstream quota and our own approved cost budget.

Initial official-source snapshot (recheck before release; not a permanent guarantee):

| Provider | Verified free-access signal | Important caveat / GLAFIRA control |
|---|---|---|
| Google Gemini API | Selected models, including Gemini 3.8 Flash and 3.1 Flash-Lite, show free input/output tier | Per-project/model limits change; free-tier content may be used to improve Google products; exclude sensitive data unless terms permit |
| GroqCloud | Model-specific rate-limit table; example GPT-OSS 120B: 30 RPM, 1,000 RPD, 8K TPM, 200K TPD | Org-level limits and exceptions; table is not a promise of equal limits for all users |
| Mistral La Plateforme | Free experimentation/prototyping mode | Current RPS/TPM/month caps appear in account console and vary |
| Cohere Trial API | Trial key free; Chat example 20 calls/min, Embed 5/min; other endpoints 1,000 calls/month | Endpoint-specific; not a production key |
| OpenAI API | Metered public price catalogue; account grants/tier are account-specific | Do not assume ChatGPT subscription includes API credits; treat as paid unless billing page proves grant |
| Anthropic API | No general free production allowance assumed | Check actual Console credit/terms; do not promise free API |
| DeepSeek API | API pricing/granted-balance is separate from consumer app free use | Do not infer app access means free API |
| xAI API | Prepaid API credits and spending limits documented | Consumer Grok plan and API credits are separate |
| Yandex AI Studio / GigaChat | Trial and consumer access may differ from commercial API quotas | Check region, product, model and account terms before assigning any free amount |

Catalogue each provider with official URL, checked_at, account/project, model, region, RPM/RPD/TPM/TPD/monthly spend, reset timezone, commercial-use conditions, data retention/training terms and effective dates. Stale/unknown quota blocks automatic free routing.

Customer free allowance is a defined included amount, not unlimited AI. On exhaustion, stop, ask permission to spend GLF, or use a compatible local model. Never silently switch to paid provider.

## 8. Telegram storefront and payment model

Five initial catalogue items: free GLAFIRA download; VPN 30-day plan; AI 30-day plan/GLF credit; combined VPN+AI plan; GLF contribution/donation information (not an investment or guaranteed return).

AI and VPN sold inside a Telegram bot/Mini App are digital services. Telegram's current Developer Terms require in-Telegram digital purchases to use Telegram Stars (XTR). Direct RUB or TON/crypto payment inside the Telegram app is not a compliant substitute. Stars are not GLF and not a general wallet-to-wallet rail. Verify Stars payment server-side, then grant service/GLF allowance in our separate ledger. External web checkout is a separate channel requiring platform/store and jurisdiction review.

Bot requirements: idempotent invoice payloads, signed update validation, reconciliation, duplicate/refund handling, /paysupport, audit and delivery confirmation. No service activation before verified successful payment. Bot secrets stay server-side.

Official references:
- https://core.telegram.org/bots/payments-stars
- https://telegram.org/tos/bot-developers
- https://telegram.org/tos/stars
- https://core.telegram.org/bots/api#sendinvoice

## 9. Decisions requiring owner approval

After benchmarks, approve: GLF reference value and update cadence; contributor reward share/caps/issuance budget; package expiry/rollover/inactive-day pause; initial local and hosted AI free allowance; per-model AI caps; VPN active-day definition and outage compensation; Telegram Stars price/net revenue accounting; consumer disclosures for credits/refunds/expiry/transfers/external value.

GLF is not a security, investment, yield product or guaranteed cash equivalent. Never advertise financial returns from device contribution.

## 10. Sources and refresh discipline

Official pages checked 2026-11-20:
- Gemini API pricing/free tier: https://ai.google.dev/gemini-api/docs/pricing
- Groq rate limits: https://console.groq.com/docs/rate-limits
- Cohere API trial limits: https://docs.cohere.com/v2/docs/cohere-faqs
- OpenAI API pricing: https://platform.openai.com/pricing/
- DeepSeek API pricing: https://api-docs.deepseek.com/quick_start/pricing/
- xAI billing: https://docs.x.ai/console/billing
- Telegram digital services: https://core.telegram.org/bots/payments-stars and https://telegram.org/tos/bot-developers

Recheck official pages and actual account console before changing an allowance or price.
