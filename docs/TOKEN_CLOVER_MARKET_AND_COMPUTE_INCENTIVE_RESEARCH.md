# Project «Токен» / Clover — initial market and incentive research

**Research snapshot:** 2026-11-20  
**Currency reference:** Bank of Russia official USD rate for 2026-11-20: 81.9290 RUB/USD.  
**Status:** preliminary, source-linked desk research; not a financial forecast, legal opinion or production pricing decision.

## 1. Initial answer: Yandex or Grok — who charges more for model work?

For the current public API list prices examined here, **YandexGPT Pro 5.1 is more expensive than Grok 4.7 on both input and output tokens** after approximate conversion at the CBR rate. This is a tariff comparison, not a claim that the models have equal quality.

| API model / provider | Input per 1M tokens | Output per 1M tokens | Currency / notes |
|---|---:|---:|---|
| YandexGPT Pro 5.1 | 800 RUB | 800 RUB | Official Yandex AI Studio price: 0.8 RUB per 1,000 input and output tokens, VAT included |
| Grok 4.7 | about 164 RUB | about 492 RUB | Official xAI: $2 / $6 per 1M input/output; converted at 81.929 RUB/USD; tax/payment costs not normalized |
| Alice AI LLM Flash | 100 RUB | 200 RUB | Official Yandex AI Studio: 0.1 / 0.2 RUB per 1,000 |
| DeepSeek V4.1 Flash via Yandex AI Studio | 300 RUB | 500 RUB | Official Yandex AI Studio hosted tariff; not necessarily DeepSeek's direct API tariff |

Derived comparisons:
- YandexGPT Pro 5.1 input is about **4.9×** Grok 4.7 input at these public rates.
- YandexGPT Pro 5.1 output is about **1.6×** Grok 4.7 output.
- A simple workload of 1M input + 250k output tokens costs about 1,000 RUB on YandexGPT Pro 5.1 versus about 287 RUB on Grok 4.7 (before taxes, payment friction, regional uplifts, retries, tools and quality differences).
- Grok 4.7 cached input is $0.50 / 1M (about 41 RUB / 1M at the same rate); YandexGPT Pro 5.1's published cached-token price is 0.8 RUB / 1,000 (800 RUB / 1M). Cache semantics and cache eligibility must be normalized before using this as a real workload comparison.
- xAI's US regional endpoint adds a 10% premium. Grok prompts reaching 200k context use higher long-context rates; priority processing can double token rates when the response is actually served at the priority tier.

**Important:** token prices alone do not establish which model is cheaper per accepted task. Different tokenizers, reasoning effort, context, retries, tool calls, image/audio units, output length, model quality and correction effort matter. The comparison must be repeated on identical benchmark tasks and cost per accepted deliverable.



## 1A. Extended provider-price snapshot (additional official tariffs)

The following snapshot expands the comparison beyond Yandex and xAI. USD amounts are converted using 81.929 RUB/USD solely for orientation; the original USD rates remain authoritative. These are list rates for different model versions, not a quality ranking.

| Provider / model | Input per 1M | Output per 1M | Approx. RUB input/output | Notes |
|---|---:|---:|---:|---|
| DeepSeek V4.1 Flash direct API (peak) | $0.30 | $1.20 | 24.58 / 98.31 | Peak period; off-peak is half; cached input is $0.006/M peak and $0.003/M off-peak |
| OpenAI GPT-6.1-Sol | $1.00 | $5.00 | 81.93 / 409.65 | Official price page; cached input $0.05/M; context tier and service tier affect cost |
| Google Gemini 3.1 Flash-Lite Preview | $0.25 | $1.50 | 20.48 / 122.89 | Free tier exists with limits; free-tier data-use terms differ from paid |
| Mistral Small 4 | $0.15 | $0.60 | 12.29 / 49.16 | Official API price card |
| Mistral Large 4 | $0.70 | $2.10 | 57.35 / 172.05 | Official API price card |
| Cohere Command A | $2.50 | $10.00 | 204.82 / 819.29 | Official model card; 256k context in the published model card |

| Sber GigaChat 2 Lite / Pro / Max | not separately split in the model card | not separately split in the model card | 65 / 500 / 650 RUB per 1M tokens | Official model card presents an indicative per-million-token cost, but input/output split must be verified in the current tariff package |
| GroqCloud GPT-OSS 20B / 120B | $0.075 / $0.15 | $0.30 / $0.60 | 6.14 / 24.58 input; 24.58 / 49.16 output | Official Groq model list; rate limits and developer-tier payment requirements matter |
| Alibaba Cloud Qwen3.5-Plus (Global, ≤128k) | $0.115 | $0.688 | 9.42 / 56.35 | Official Model Studio; international region pricing is higher ($0.40 / $2.40), so region selection is material |

### What the expanded snapshot changes

- Sber/GigaChat has an indicative official model-page price card of 65 / 500 / 650 RUB per million tokens for Lite / Pro / Max, but the model page does not split the charge into input and output. The commercial tariff package and the current Cloud.ru delivery path must be verified before using it as a cost assumption. GigaChat 3 Ultra is documented as a Freemium model for individuals; this is not a substitute for a paid commercial integration.
- GroqCloud offers open-weight models such as GPT-OSS 20B/120B at $0.075/$0.30 and $0.15/$0.60 per million input/output tokens. Its free/developer limits and payment onboarding are separate from these list prices.
- Alibaba Qwen3.5-Plus has region-dependent rates: the official card lists $0.115/$0.688 per million for Global deployment up to 128k, while Singapore international pricing is $0.40/$2.40. This is a useful example of why GLAFIRA must store region/deployment with every price.


- Direct DeepSeek V4.1 Flash peak pricing is about $0.30/$1.20 per million input/output tokens, with off-peak rates at half. That is very different from Yandex AI Studio's managed DeepSeek V4.1 Flash listing of 0.3/0.5 RUB per 1,000 tokens (300/500 RUB per million). The apparent difference is large: Yandex-managed input is roughly 12.2× direct DeepSeek peak input after FX; managed output is roughly 5.1× direct peak output. **Do not assume this is pure markup** until exact model revision, token accounting, hosting, VAT, cache treatment and service scope are verified. It is, however, a strong reason to compare direct provider access with managed/aggregated access.
- DeepSeek's peak/off-peak pricing means scheduling can materially change the cost of batchable work. This is a candidate for GLAFIRA's future task scheduler only when deadline and service terms permit it.
- Gemini's free API tier is not equivalent to paid production use: it has limited model access/rate limits and its terms say submitted content may be used to improve products; paid usage has higher limits and states content is not used to improve products. Free is not necessarily suitable for confidential GLAFIRA project data.
- Mistral Small 4 / Large 4 show why model routing by task matters: the price spread between fast/small and large models is substantial.
- Cohere's Command A is much more expensive per token than low-cost Flash models, but is marketed for agentic/RAG/enterprise workflows. Compare accepted task quality, not just price.

### Updated simple-workload comparison

For 1M input + 250k output tokens:
- YandexGPT Pro 5.1: about 1,000 RUB (0.8 + 0.8×0.25 RUB per 1,000 tokens).
- Grok 4.7 below 200k prompt context: about $3.50, or 286.75 RUB at the CBR rate.
- Direct DeepSeek V4.1 Flash peak: about $0.60, or 49.16 RUB; off-peak about $0.30, or 24.58 RUB.
- Yandex-hosted DeepSeek V4.1 Flash: about 425 RUB at its listed managed tariff.

These numbers are deliberately presented as a tariff illustration only. A real GLAFIRA comparison must include task quality, output size, reasoning tokens, retries, cache hit, tool/search calls, image/audio/video charges, latency, rate limits, regional availability, payment costs, tax and the cost of correcting a failed answer.


## 2. API usage is not the same thing as a consumer subscription

The following are separate products and must not be mixed in one price column:
- API metering: input/output/cached/reasoning tokens, modalities, tools, batch and regional multipliers.
- Consumer subscriptions: fixed monthly fee with usage caps/rate limits and bundled features.
- Provider credits: prepaid account balances that are usually not interchangeable across providers.
- GLAFIRA internal GCU/GLF: measured compute-work units and a separate settlement/reward system; neither is a model's API token.

Current subscription comparison points:
- xAI's official X Subscription page lists Premium at $30/month, Super at $100/month and Ultra at $200/month. Premium includes SuperGrok, Cursor Pro and X Premium+; usage pools and eligibility differ by tier.
- OpenAI's official Help Center lists ChatGPT Plus at $20/month (about 1,639 RUB at the CBR rate); xAI's Premium bundle is $30/month (about 2,458 RUB). These are not directly comparable with GLAFIRA's proposed 500 RUB/month because included usage, availability, model access, service scope and limits differ. Local checkout, payment and regional access can differ.
- Yandex lists Alice Plus at 199 RUB/month, but it is an add-on to Yandex Plus, not a standalone subscription. GigaChat 3 Ultra is documented as a Freemium model for individuals; GigaChat's commercial terms restrict Freemium use to personal, non-commercial purposes, and new API customers from 1 September 2026 are directed to Cloud.ru for paid model access. Therefore the competitive set includes free consumer products, but those free tiers cannot automatically be reused as commercial backend capacity.

## 3. VPN + GLAFIRA proposed launch bundle

Owner's proposed launch prices:
- VPN: 300 RUB/month.
- Full GLAFIRA access: 500 RUB/month.
- Combined: **800 RUB/month**, with separate token rewards for verified device contribution.

Reference prices observed:
- Amnezia Premium official payment page: $32 / 6 months, $54 / year, $84 / 2 years. At the 2026-11-20 CBR rate this is approximately 437, 369 and 287 RUB/month respectively. Official docs say a one-month subscription is available only through the mobile app stores; the website plans are longer terms. Amnezia documents support for 7 devices, up to 200 Mbps, and locations designed for restrictive networks.
- Kaspersky Secure Connection official Russian page: 2,299 RUB for the first year (5 devices), about 192 RUB/month for the first year; renewal is listed at 2,499 RUB/year.
- Other market comparisons (including AdGuard VPN) show multi-year effective monthly prices that can be below 300 RUB, while monthly prices can be substantially higher. Exact current Russian checkout prices, availability and payment routes need a separate verification pass.

**Initial commercial assessment:** 300 RUB/month for VPN is credible and within the competitive band, but it is not a price advantage by itself. Some competitors undercut it on annual or multi-year prepayment; the product must win on connection reliability in Russian networks, protocol agility, support, privacy, multi-device use and transparent terms. Do not advertise a bypass guarantee or unlimited performance that the infrastructure cannot sustain.

At 500 RUB/month, GLAFIRA full access would be far below a $30 Grok subscription converted at the CBR rate, but Russian users can already use several free AI chats. The offer needs a clearly demonstrated bundle: access to multiple model providers where licensed/available, smart routing by task/cost, agent workflows, persistent project context, file handling, Android + Windows Desktop + Agent, voice, integrations and a coherent work history. "Several models" alone is not a durable differentiator because other aggregators can also offer multiple models.

The 800 RUB combined bundle is promising as an introductory offer **only if**:
1. model usage has explicit fair-use limits and no hidden paid fallback;
2. VPN infrastructure cost per active user is known at real traffic levels;
3. support, payment fees, taxes, refunds, app-store commissions and fraud are included;
4. 500 RUB access does not promise unlimited frontier-model API usage;
5. a pilot measures activation, day-7/day-30 retention, paid conversion, actual model mix, average cost per active subscriber, VPN bandwidth and support burden.

## 4. Device contribution: what the user should do instead of tapping a screen

The core proposal is materially different from "tap-to-earn" games: a customer installs GLAFIRA, chooses whether to contribute unused device resources, and can continue using the phone normally. No artificial tapping, watch-time, ad-clicking, fake engagement or forced 24/7 uptime.

### Lessons from existing projects

**Honeygain / bandwidth sharing (user reports, not audited averages):**
- A Reddit user reported $0.476 over seven days while using 7.21 GB of mobile data on a Galaxy S23. This illustrates why mobile-data sharing can be a poor deal for a user on a metered cellular plan.
- Another Reddit user reported roughly $5.75/month on one desktop, with additional bonus/referral amounts. This is an individual report and is not a guaranteed earnings rate.
- Lesson: show data transferred and net reward; default to Wi-Fi only; never encourage cellular data consumption to chase rewards.

**Grass / points and opaque rewards (community anecdote):**
- A high-engagement Reddit discussion in July 2026 complained that long-running users with many uptime points received very small rewards and that the reward formula/weighting was perceived as opaque and changeable.
- Lesson: do not reward mere uptime points, do not publish an opaque "score", and do not promise a token conversion that can later be changed without transparent governance and notice.

**Salad / PC GPU compute (official provider documentation):**
- Salad states that earnings depend on GPU model, customer demand, job priority, uptime and region; there is no fixed guaranteed rate. Jobs may have gaps.
- Salad says its main compute workloads run for customers, with bandwidth sharing and mining as secondary/backup workloads. Reward balance is redeemed in a storefront and is not the same as a bank cash payout.
- Its November 8–14, 2026 published examples showed RTX 4090 average earnings around $0.270 per chopping hour, with the middle half at $0.243–$0.293; this is high-end desktop GPU compute, not a smartphone benchmark.
- Lesson: real buyer demand and hardware capability drive earnings. Do not extrapolate PC GPU rates to phones. Distinguish gross reward from incremental electricity, wear, network and opportunity costs.

### Proposed GLAFIRA contribution design

1. **Opt-in, granular consent:** separate toggles for CPU, NPU/GPU, storage, and network/bandwidth; each is off by default. The user can set time windows and resource caps.
2. **Phone-first safety:** preserve the existing owner ceiling: smartphones contribute at most 10% of the available compute budget, Wi-Fi only, never cellular data. Run only while charging, screen idle, battery above an owner-set threshold, and device temperature within a safe limit. Pause immediately on heat, low battery, user activity, connectivity change or OS restriction. The 10% is a ceiling, not a promised sustained utilization. Respect Android background-execution and app-store policies. Windows Desktop/home PC may have a separately configurable 20–100% idle resource ceiling, immediately yielding to owner activity; benchmark and expose actual usage.
3. **Small useful jobs:** prioritize compact, interruptible, verifiable tasks that benefit from heterogeneous edge devices. Do not make phones download huge models or hold user data. Avoid jobs that require continuous real-time availability.
4. **No local file access by default:** compute workers receive only an explicit, minimal, task-scoped payload. Use sandboxed execution, signed workloads, timeouts, resource ceilings and no arbitrary shell/code execution.
5. **Measured work, not idle time:** reward completed and verified useful work, weighted by accepted output quality and actual measured resources. No rewards for simply staying online, opening the app, tapping, referrals alone or unverified device claims.
6. **Visible cost and benefit:** show CPU/NPU/GPU time, energy estimate/measurement confidence, data volume, temperature pauses, reward earned, pending verification and withdrawal/use rules. Never present estimates as measured facts.
7. **Anti-fraud and fairness:** device attestation only where proportionate and privacy-preserving; duplicate-task detection, signed results, challenge/recompute sampling, reputation based on task outcomes rather than personal identity, and appeal process.
8. **Reward utility:** users should be able to spend earned units on GLAFIRA usage, VPN days or a subscription discount. Any transfer, cash redemption or public exchange needs separate legal, tax, financial and anti-fraud review.
9. **No coercion:** subscription access must not be conditional on compute contribution. Non-contributors get the same paid product at the published price; contributors receive a transparent optional reward.
10. **Pilot first:** begin with a small, opt-in beta and publish measured median earnings per device class, task availability, energy/data cost and net benefit. Stop a task class if user benefit is negative or unreliable.

**Critical economic caveat:** a smartphone CPU contribution may have very low market value after coordination, network, verification and energy costs. The project should not build a token economy on the assumption that every phone can generate meaningful rewards. First prove buyer demand for tasks that can run safely and cheaply on the actual enrolled device fleet.

## 5. GLAFIRA differentiation hypothesis

GLAFIRA should not position itself as "another chatbot with many model logos." The stronger hypothesis is a personal AI work environment:
- multi-provider routing and model choice under one owner-controlled budget;
- model selection based on task quality/cost/latency rather than a single universal winner;
- specialist agents with persistent project context and auditable work;
- Android, Windows Desktop and optional Windows Agent compute;
- local-first/owner-controlled execution for appropriate tasks;
- files, voice, tools and integrations within one permission/approval model;
- transparent GCU measurement and GLF reward accounting.

This is a hypothesis to validate through a competitive feature matrix and user interviews. It must not be marketed as implemented until the features exist and pass acceptance.



## 5A. Competitive reality check: multi-model access is not enough

The current competitor landscape makes one point clear: **GLAFIRA cannot win by saying only "we have several models in one app."** Several products already cover parts of that proposition.

| Competitor archetype | What is already offered | Implication for GLAFIRA |
|---|---|---|
| DeepSeek (model + app/API platform) | DeepSeek's current API can expose web search, multi-step deep-thinking search, file parsing, URL reading and voice-related app capabilities | A model provider can add app-like tools through API. GLAFIRA must add persistent cross-project orchestration and device/network ownership, not just proxy model calls |
| Poe (consumer model aggregator) | One subscription exposes many major models/bots, images/video/audio; starts at $4.99/month with daily/monthly compute points; extra points can be purchased | This directly undercuts a 500 RUB GLAFIRA tier on entry price. Poe points are usage-limited, non-transferable, non-cash and normally do not roll over; GLAFIRA must explain its additional work value and token utility |
| OpenRouter (developer model router/gateway) | One API key across many providers, model catalog, provider routing/fallback, price/latency/throughput controls, budgets, free models; standard/business credit purchases incur platform fees | Model aggregation, fallback and cost routing are not unique. GLAFIRA's routing must connect to project/task state, accepted-output quality, specialist agents and user-owned execution |
| ChatGPT Projects / Work (AI work environment) | Project-scoped chats, files, instructions and memory; cross-device continuity, connected apps and deep research; longer multi-step Work/browser processes | Projects, memory, files, apps and agentic research are already strong competitors. GLAFIRA needs more control over local/peer execution, Windows Agent fleet, owner-run Mother and the user compute marketplace |
| Yandex / GigaChat (regional assistants and APIs) | Russian-language assistant ecosystems, local payments and API products, plus free/low-cost consumer entry points | GLAFIRA must win on cross-provider neutrality, actual workflows, local/desktop integration and transparent pricing—not merely Russian-language support |
| DePIN / bandwidth / compute networks (Salad, Grass, Honeygain, Golem etc.) | Reward people for providing GPU compute, bandwidth or other resources | They validate the supply-side incentive concept but do not deliver the complete personal AI office. GLAFIRA can connect user-owned devices to real task demand, but must prove safe, profitable workloads |

### Pricing implications

- Poe's official Help Center advertises multi-model access from $4.99/month. At the 2026-11-20 CBR rate this is about 409 RUB before local pricing/tax. It is therefore cheaper than the proposed 500 RUB GLAFIRA AI subscription, although its compute points and model-specific usage limits mean it is not unlimited.
- OpenRouter's free tier advertises 25+ free models and 4 free providers; paid inference is billed at provider list price, with a 5.5% credit-purchase fee on Standard and 8% on Business. It is a developer router rather than a consumer AI office.
- ChatGPT Projects already combine project chats, files, instructions, memory and cross-device continuation; Deep Research creates cited reports, and Work handles longer multi-step tasks. GLAFIRA must not claim these capabilities are unique.
- DeepSeek's November 2026 API announcement exposes web search, deep-thinking search, files, URL reading and voice-oriented building blocks. This reduces the defensibility of a simple "we integrate models plus web/file/voice" pitch.

### What GLAFIRA must become to be meaningfully different

The defensible target is **a user-owned distributed AI office and service network**, not a thin multi-model frontend:
1. **Persistent projects as operational workspaces:** tasks, decisions, files, agent roles, source evidence, deliverables and history remain linked across devices.
2. **Multi-agent execution with accountability:** a coordinator decomposes work, assigns specialist agents, manages dependencies, verifies outputs and records what was actually completed.
3. **User-owned execution capacity:** Windows Desktop is the owner's control center; Windows Agents and opt-in participant devices execute isolated, bounded tasks under owner consent and Core policy.
4. **A real compute marketplace:** buyers post funded tasks; provider GLAFIRAs accept scoped leases; results are verified; rewards settle only after acceptance. This is distinct from points for uptime or tapping.
5. **Mother + VPN service plane:** one account, device enrollment, internal wallet/ledger and optional VPN entitlement, separated from the VPN data plane.
6. **Transparent economics:** per-task usage receipts, GCU measurement, GLF reward accounting, hard buyer budgets and explicit provider costs.
7. **Portability and owner control:** provider-neutral model registry, local/remote execution choice, exportable project data, revocable device participation and no mandatory lock-in to one model company.

**Current maturity warning:** an external technical review on 2026-11-18 characterized the then-current GLAFIRA build as an Android LLM-orchestration client with nascent multi-agent logic; task decomposition/distribution, Web/Browser Agent, Desktop Bridge and CRM were still planned. The owner's accepted product direction was a full AI office. Treat the seven points above as target differentiators, not present-tense capabilities, until each is implemented and accepted.

### Go-to-market conclusion

The 800 RUB bundle can be attractive as an introductory package, but it is not automatically a price win:
- VPN at 300 RUB is close to Amnezia's two-year effective monthly rate and above some first-year promotional VPN rates.
- AI at 500 RUB is above Poe's advertised entry price (about 409 RUB at the CBR rate), but could justify the difference if it includes useful persistent projects, agents, local/desktop execution and a real compute reward marketplace.
- The combined 800 RUB package must show measurable daily utility. A customer should understand what they can do with GLAFIRA that they cannot do by paying for Poe or using free DeepSeek/Yandex/GigaChat plus a separate VPN.
- Run a pilot and publish real usage limits. Never imply unlimited frontier models or guaranteed token earnings.

### Historical context for the selected model stack

In the prior project discussions, DeepSeek was used as a technical expert/reviewer, not simply chosen as the product to copy. Gemini and Anthropic were also accepted as independent PR reviewers, with the Anthropic API cost and Gemini free-tier data-use caveat explicitly noted. The competitor matrix above is a fresh product-market comparison and should not be confused with that reviewer stack.

## 6. Next research actions

- Validate and normalize the ten initial provider entries, then expand the API table to at least fifteen providers using official price cards; capture input/output/cached/reasoning, image/audio/video, batch, long context, regions, free tiers, effective dates and tax treatment.
- Compare direct DeepSeek API with DeepSeek hosted through Yandex and other aggregators; record routing/markup differences.
- Benchmark YandexGPT, Grok, DeepSeek, GigaChat and selected alternatives on the same Russian-language business tasks; calculate cost per accepted deliverable.
- Verify the exact consumer prices and checkout availability for ChatGPT, Claude, Gemini, DeepSeek, GigaChat, Alice, Poe and Grok in Russia.
- Build VPN competitor matrix: monthly vs annual effective price, devices, traffic, protocols, anti-blocking capability, support, refund and payment route.
- Research DePIN compute projects (Salad, Grass, Honeygain, Golem, Akash, io.net and others) using official economics docs plus clearly labelled user reports.
- Build a feature-by-feature competitor matrix for Poe, OpenRouter, ChatGPT Projects/Work, DeepSeek, Yandex/GigaChat and VPN/DePIN projects; validate Russian availability and real checkout prices.
- Estimate unit economics for 100, 1,000 and 10,000 subscribers and for several device participation rates. Include API, VPN egress, compute verification, rewards, support, payment, tax, fraud and churn.
- Review Russian legal and platform requirements for VPN distribution, crypto/token rewards, consumer subscriptions, payment processing, advertising and personal data before public launch.

## 7. Sources and evidence log

### Primary pricing / product sources
- Yandex AI Studio pricing (official; Russian ruble prices, VAT included, cache/tool/async distinctions): https://aistudio.yandex.ru/ru/docs/ai-studio/pricing
- xAI API pricing (official; Grok model token rates, long-context, cached, priority and regional pricing): https://docs.x.ai/developers/pricing
- xAI release notes (official; Grok 4.7 model and dated API rate card): https://docs.x.ai/developers/release-notes
- xAI Grok 4.7 model page (official; 500k context and token rates): https://docs.x.ai/developers/models/grok-4.7
- xAI consumer pricing (official): https://x.ai/pricing
- X Subscription official announcement (Premium $30/month; includes SuperGrok, Cursor Pro and X Premium+): https://x.ai/news/x-subscription
- OpenAI ChatGPT Plus subscription terms (official; $20/month, API billed separately): https://help.openai.com/en/articles/6950777-what-is-chatgpt-plus
- X Subscription announcement (official; $30/$100/$200 tiers): https://x.ai/news/x-subscription
- X Premium plan help (official; regional/local prices vary): https://help.x.com/en/using-x/x-premium
- DeepSeek direct API pricing (official; peak/off-peak, cache-hit/miss and output prices): https://api-docs.deepseek.com/quick_start/pricing/
- OpenAI API pricing (official; per-model input/output/cache and service tiers): https://platform.openai.com/pricing/
- Google Gemini API pricing (official; free/paid, batch/flex/priority and data-use distinctions): https://ai.google.dev/gemini-api/docs/pricing
- Mistral API pricing (official; current model family price card): https://docs.mistral.ai/inference/pricing
- Cohere Command A model card (official; input/output prices and model specs): https://docs.cohere.com/v1/docs/command-a
- GroqCloud supported models (official; model-specific token prices and rate limits): https://console.groq.com/docs/models
- Alibaba Cloud Qwen3.5-Plus pricing (official; region and context-tier prices): https://www.alibabacloud.com/help/en/model-studio/qwen3-5-plus
- Sber GigaChat 2 Max model page (official; indicative per-million-token price and model capability): https://developers.sber.ru/docs/ru/gigachat/models/gigachat-2-max
- Sber GigaChat individuals quickstart (official; new customer API access routed via Cloud.ru from 2026-09-01): https://developers.sber.ru/docs/ru/gigachat/individuals-quickstart
- Sber GigaChat commercial use terms (official; Freemium is personal/non-commercial): https://developers.sber.ru/docs/ru/gigachat/tariffs/commercial
- Bank of Russia USD rate for 2026-11-20: https://www.cbr.ru/currency_base/daily/?UniDbQuery.Posted=True&UniDbQuery.To=20.11.2026
- Amnezia Premium payment page (official): https://pay.amnezia.org/
- Amnezia official FAQ (plans, 7 devices, locations, 200 Mbps, monthly purchase conditions and payment): https://docs.amnezia.org/ru/faq/
- Kaspersky Secure Connection Russian pricing (official): https://www.kaspersky.ru/secure-connection
- Yandex Alice Plus option terms/help (official; add-on price/conditions): https://alice.yandex.ru/support/ru/station/settings/services.html

### Compute and incentive model sources
- Salad earnings FAQ (official; no fixed rate, demand and hardware dependence): https://support.salad.com/faq/jobs/how-much-can-i-earn-with-salad/
- Salad profitability and electricity calculation (official): https://support.salad.com/faq/jobs/calculating-profitability-and-electricity-costs/
- Salad business model (official; buyer-funded compute and reward margin): https://support.salad.com/faq/about-salad/what-is-salads-business-model/
- Salad GPU earning rates, Nov 8–14, 2026 (official): https://app.salad.io/earning-rates/
- Honeygain one-week mobile-data user report (Reddit; anecdotal, not audited): https://www.reddit.com/r/passive_income/comments/1uktptb/i_ran_honeygain_unetwork_sidebyside_for_7_days/
- Honeygain desktop earnings user report (Reddit; anecdotal): https://www.reddit.com/r/passive_income/comments/1m56zhk/
- Grass rewards transparency/trust discussion (Reddit; anecdotal community report): https://www.reddit.com/r/Grass_io/comments/1uossn5/grass_is_about_to_lose_the_one_thing_every_depin/

### Evidence discipline
Every price must carry retrieval date, currency, billing unit, region, tax basis, model/version and source URL. User reports must remain anecdotes, not population averages. Marketing claims, live payout data, and audited measurements must be distinguished. Refresh prices before quoting customers or making financial commitments.


## 7A. Dated tariff comparison: YandexGPT Pro 5.1 vs Grok 4.7 (checked 2026-11-20)

This comparison resolves an important ambiguity: **there is no single Yandex price**. Yandex AI Studio publishes separate synchronous and asynchronous tariffs. Compare like with like and preserve the latency/service mode.

### Public API price card

All Yandex RUB values below include VAT according to the official tariff page. xAI prices are USD, before any currency conversion or bank/foreign-exchange fees.

| Model / mode | Input per 1K | Cached input per 1K | Tool tokens per 1K | Output per 1K | Billing basis |
|---|---:|---:|---:|---:|---|
| YandexGPT Pro 5.1 synchronous | 0.80 RUB | 0.80 RUB | 0.20 RUB | 0.80 RUB | Live synchronous request; VAT included |
| YandexGPT Pro 5.1 asynchronous | 0.41 RUB | Not separately listed | Not separately listed | 0.41 RUB | Async mode; VAT included |
| Grok 4.7 standard | $0.002 | $0.0005 | Tool billing depends on the tool | $0.006 | Public xAI API, short context below 200K |
| Grok 4.7 long context | $0.004 | $0.001 | Tool billing depends on the tool | $0.012 | Applies to all tokens once prompt crosses 200K |
| Grok 4.7 priority | 2× standard | 2× cached | 2× applicable token rate | 2× standard | Only when response confirms priority service tier |
| Grok 4.7 batch | 20% below standard | 20% below standard | 20% where applicable | 20% below standard | Async batch; most jobs typically finish within 24 hours |

### RUB normalization using the official 20-Nov-2026 USD rate

Reference FX: **1 USD = 81.9290 RUB** on 2026-11-20. This is an analytical normalization, not a guaranteed settlement rate.

| Model / mode | Input per 1K | Cached input per 1K | Output per 1K |
|---|---:|---:|---:|
| YandexGPT Pro 5.1 synchronous | 0.800 RUB | 0.800 RUB | 0.800 RUB |
| YandexGPT Pro 5.1 asynchronous | 0.410 RUB | — | 0.410 RUB |
| Grok 4.7 standard | 0.164 RUB | 0.041 RUB | 0.492 RUB |
| Grok 4.7 long context ≥200K | 0.328 RUB | 0.082 RUB | 0.983 RUB |

### Which is more expensive?

- **Synchronous YandexGPT Pro 5.1 vs standard Grok 4.7:** Yandex is about **4.88× more expensive for input** and **1.63× for output** at the published rates. Yandex's listed cached-input price is about 19.5× Grok's cached-input price.
- **Asynchronous YandexGPT Pro 5.1 vs standard Grok 4.7:** Yandex is about **2.50× more expensive for input**, but its 0.41 RUB output token is about **16.6% cheaper** than Grok's normalized 0.492 RUB output token.
- For a simple illustrative workload of 10,000 input + 2,000 output tokens, before tools/retries/FX fees: synchronous Yandex ≈ 9.60 RUB; asynchronous Yandex ≈ 4.92 RUB; Grok 4.7 ≈ 2.62 RUB. This is price arithmetic only—not an equal-latency or equal-quality benchmark.
- Long-context, priority, batch, cached tokens, tool calls and reasoning accounting can change the comparison materially. Never multiply only the visible input/output rates if the task uses additional modalities or services.

**Conclusion:** for the published standard synchronous API tariffs, YandexGPT Pro 5.1 costs more than Grok 4.7 on both input and output. If comparing Yandex's cheaper asynchronous tariff, Yandex still costs more on input, but Grok costs more on output. The statement "Yandex is always more expensive" is therefore too broad.

### Consumer offer and VPN price context

- Amnezia Premium's official Russian page currently advertises one subscription for up to 7 devices, speeds up to 200 Mbps, a 14-day refund window and an advertised starting point from 325 RUB/month. Its displayed multi-month effective prices are 448 RUB/month for 6 months, 365 RUB/month for 1 year and 290 RUB/month for 2 years.
- A proposed GLAFIRA VPN price of 300 RUB/month is below Amnezia's displayed 6-month and annual effective monthly rates, close to (but 10 RUB above) its 2-year effective rate, and below its 325 RUB headline starting point. That is competitive on price, but GLAFIRA must match or clearly explain differences in reliability, protocol resilience, locations, devices, support and refunds.
- Poe's official help page states that multi-model subscriptions start at $4.99/month and include usage-limited compute points; unused points generally do not roll over unless a plan says otherwise. At the 20-Nov-2026 FX reference this is approximately 409 RUB before regional pricing/tax/payment friction. Therefore the proposed 500 RUB GLAFIRA AI tier is not the cheapest multi-model entry point; its premium needs to be justified by a persistent AI office and actual workflows, not simply access to several models.
- Yandex AI Studio's Go subscription is a business/employee product priced at 2,490 RUB per employee/month including VAT (with larger Go X5/X15 plans). Do not compare this B2B per-seat plan directly with consumer chatbot subscriptions without explaining the difference.
- Regional availability, accepted payment methods, account eligibility and local checkout prices must be checked separately before any Russian consumer launch. API pricing is not the same thing as consumer subscription pricing.

### Source snapshots

- Yandex AI Studio official tariff page, including sync/async tables and VAT statement: https://aistudio.yandex.ru/ru/docs/ai-studio/pricing
- xAI official Grok 4.7 model card (input $2/M, cached $0.50/M, output $6/M, 500K context): https://docs.x.ai/developers/models/grok-4.7
- xAI official API pricing, including long context, priority, batch and tool/media pricing: https://docs.x.ai/developers/pricing
- xAI official Grok 4.7 announcement: https://x.ai/news/grok-4-7
- Bank of Russia USD historical rate: https://www.cbr.ru/currency_base/daily/?UniDbQuery.Posted=True&UniDbQuery.To=20.11.2026
- Amnezia Premium official Russian tariff page: https://amnezia.org/ru/premium
- Poe official purchase FAQ (subscription from $4.99 and compute-point conditions): https://help.poe.com/hc/en-us/articles/19945140063636-Poe-Purchases-FAQs

**Confidence:** high for the published source prices and FX arithmetic as of the stated snapshot; medium for Russian consumer competitiveness because checkout availability, regional pricing and payment acceptance vary. No quality equivalence is implied.


## 5B. Grok Bot: a real cloud-computer competitor (checked 2026-11-20)

Grok Bot is a direct competitor to the planned GLAFIRA AI-office experience—not merely a chat model.

| Tier | Published monthly price | Included usage |
|---|---:|---|
| Premium | $30 (~2,458 RUB at 81.929 RUB/USD) | Grok Bot cloud computer, tool sign-in, scheduled routines, desktop/mobile, weekly Bot usage |
| Super | $100 (~8,193 RUB) | Premium plus 3.5× Premium usage and priority model access |
| Ultra | $200 (~16,386 RUB) | Super plus 12× Premium usage and earliest access |

Important product details:
- Each user's Bots share one persistent cloud computer (files, browser sessions and logins). The isolation boundary is per user, not per Bot; separate Bots are not separate security compartments.
- Bots can run in parallel and continue on the cloud computer when the user's laptop is closed.
- Windows, macOS and Linux desktop clients plus iOS/Android companion apps share the same Bots, conversations, routines and cloud computer.
- The user can explicitly allow a Bot to execute on their local computer; local execution is not the default security boundary.
- Usage is weekly pooled; extra usage can be billed based on token cost. This is not unlimited agent execution.
- Routines have rate limits (up to 300 runs/hour across all routines and 200/hour per routine); a failed run is not automatically retried. Frequent routines consume the weekly usage pool quickly, and unattended routines may pause after long inactivity.

**Competitive implication:** GLAFIRA must not claim that an AI teammate with a computer, tool sign-in, scheduled routines, parallel agents, mobile/desktop access or local execution with permission is unique. Grok Bot already markets all of these. GLAFIRA's defensible target is the combination of provider-neutral multi-model routing, durable cross-project task graph and evidence journal, user-controlled local Windows Desktop/Agent fleet, opt-in compute from independently owned devices, buyer-funded task marketplace, verifiable accepted-work rewards and an integrated optional VPN/service plane. These remain target-state claims until implemented and accepted.

At 500 RUB/month, GLAFIRA's AI tier is far below Grok Bot Premium (~2,458 RUB/month) but above Poe's ~$4.99 entry tier (~409 RUB at the reference FX). This does not prove that 500 RUB is attractive: the included weekly/model usage, local execution, project continuity and task completion must be clearly specified. Never compare a capped 500-RUB plan with Grok Bot's cloud computer as if both provide unlimited work.

Official sources:
- Grok Bot pricing and product: https://x.ai/bot
- Grok Bot setup, supported platforms and local execution boundary: https://docs.x.ai/grok-bot/get-started
- Grok Bot routines, usage consumption and rate limits: https://docs.x.ai/grok-bot/skills-routines-and-automations
- X Subscription shared usage pool: https://docs.x.ai/x-subscription/grok-bot

## 5C. Compute and bandwidth contribution: real rates, payout friction and design lessons

### GPU compute — Salad (not a smartphone benchmark)

Salad's official GPU earning-rate page lists *active job* rates as of 2026-11-01. For example:
- RTX 5070: $0.064–$0.160 per hour while a GPU job is running.
- RTX 5070 Ti: $0.080–$0.216 per active job-hour.
- RTX 5090: $0.200–$0.480 per active job-hour.

These are not guaranteed 24-hour earnings. Salad explicitly says electricity and time between jobs are excluded from the published active-job rate. Its live earning dashboard separately reports average earnings per chopping hour, time with a job and top-chef outliers; these metrics must not be mixed with active-job tariffs.

A community RTX 5090 owner reported ~40+ hours of high GPU utilization at ~497–500W and about $50 total, but clarified the jobs/rates were bursty and electricity cost mattered. This is one anecdote, not an average or earnings forecast.

### Bandwidth sharing — Honeygain / JumpTask (not compute)

Honeygain's current official payout rules illustrate how redemption friction affects user trust:
- Honeygain mode: $20 minimum payout, PayPal/JumpTask-credit route, published $2.02 payout fee.
- JumpTask mode: 1,000 credits = $1, cash-out from $0.50 (first payout minimum $1); gift-card/PayPal routes through the payout partner and fees vary by country.
- Honeygain mode has a 5% earning boost; JumpTask mode does not. The user can switch modes, but previously earned balances are not retroactively redirected.

This is bandwidth-sharing economics, not phone CPU/GPU compute. It should not be used to infer the earning potential of a smartphone running GLAFIRA's 10% compute ceiling.

### What GLAFIRA should learn

1. **Immediate utility first:** the AI workspace and VPN must be valuable without compute contribution. Token rewards are an optional extra, not a condition for using the service.
2. **No tapping or artificial engagement:** no daily tap, check-in, ad watch, forced screen-on time, fake visits or uptime farming. The phone should contribute only when safe and useful.
3. **Reward accepted work, not resource promises:** task result, quality, verification and requester acceptance determine reward; no pay for raw uptime or unverified benchmark claims.
4. **Net reward transparency:** show measured incremental energy, data, active time, accepted workload, resource confidence, reward formula and pending/available GLF. For PCs, include actual job occupancy and incremental kWh; for phones, include the incremental draw over charging idle.
5. **Low-friction usefulness:** small rewards should remain visible and usable inside the ecosystem if legal and unit economics permit. Avoid arbitrary high withdrawal thresholds and expiring balances. Do not promise cash redemption.
6. **Different device classes, different economics:** a smartphone at a 10% ceiling is not a GPU server. Its tasks should be small, interruptible, Wi-Fi-only and scheduled only when charging/thermal/idle conditions allow. A desktop GPU can use a different workload and reward curve.
7. **Demand-backed rewards:** if no buyer needs the work, there is no compute reward. Never mint rewards simply because a device stayed online.
8. **Payout trust matters:** publish the minimum, fees, settlement delay, region restrictions, reversals and support route before enrollment. Honeygain's $20 vs $0.50 modes show how much the cash-out route changes perceived usability.

Official sources:
- Salad GPU earning rates: https://support.salad.com/faq/compatibility/compatible-gpus-and-earning-rates/
- Salad live average dashboard: https://app.salad.io/earning-rates/
- Salad electricity/profit calculation: https://support.salad.com/faq/jobs/calculating-profitability-and-electricity-costs/
- Honeygain minimum payout and fees: https://support.honeygain.com/hc/en-us/articles/30688405604252-What-is-the-minimum-payout-threshold
- Honeygain payout methods: https://support.honeygain.com/hc/en-us/articles/30688374419228-What-are-the-payout-methods
- JumpTask mode: https://support.honeygain.com/hc/en-us/articles/30688517831708-What-is-JumpTask-mode
- JumpTask credits: https://support.honeygain.com/hc/en-us/articles/30688558061084-What-are-credits-JumpTask
- Verified user anecdote (RTX 5090): https://www.reddit.com/r/SaladChefs/comments/1vzmeyf/rtx_5090_on_salad_made_about_50_but_i_dont_think/

**Evidence caution:** Salad's rates are provider-published but are job-availability dependent; the Reddit report is anecdotal; Honeygain's thresholds are provider policy and may change. None of these sources establish that GLAFIRA smartphone compute can earn a particular amount. GLAFIRA must measure its own accepted workloads and incremental energy before publishing any reward estimate.
