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

## 2. API usage is not the same thing as a consumer subscription

The following are separate products and must not be mixed in one price column:
- API metering: input/output/cached/reasoning tokens, modalities, tools, batch and regional multipliers.
- Consumer subscriptions: fixed monthly fee with usage caps/rate limits and bundled features.
- Provider credits: prepaid account balances that are usually not interchangeable across providers.
- GLAFIRA internal GCU/GLF: measured compute-work units and a separate settlement/reward system; neither is a model's API token.

Current subscription comparison points:
- xAI's official pricing page lists SuperGrok at $30/month and SuperGrok Plus at $100/month. The X Subscription page also lists Premium at $30/month, Super at $100/month and Ultra at $200/month, with bundled X/Cursor/Grok features and included usage pools.
- At the CBR USD rate, $30 is approximately 2,458 RUB before local price differences, taxes and payment fees. This is not directly comparable with GLAFIRA's proposed 500 RUB/month because included usage, availability, model access, service scope and limits differ.
- Yandex lists the Alice Plus add-on at 199 RUB/month, but it is an add-on to Yandex Plus, not a full multi-model AI workstation. Yandex and GigaChat also offer free consumer access with limits; therefore the competitive set includes free products, not only paid subscriptions.

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

## 6. Next research actions

- Expand the API table to at least ten providers using official price cards and capture input/output/cached/reasoning, image/audio/video, batch, long context, regions, free tiers, effective dates and tax treatment.
- Compare direct DeepSeek API with DeepSeek hosted through Yandex and other aggregators; record routing/markup differences.
- Benchmark YandexGPT, Grok, DeepSeek, GigaChat and selected alternatives on the same Russian-language business tasks; calculate cost per accepted deliverable.
- Verify the exact consumer prices and checkout availability for ChatGPT, Claude, Gemini, DeepSeek, GigaChat, Alice and Grok in Russia.
- Build VPN competitor matrix: monthly vs annual effective price, devices, traffic, protocols, anti-blocking capability, support, refund and payment route.
- Research DePIN compute projects (Salad, Grass, Honeygain, Golem, Akash, io.net and others) using official economics docs plus clearly labelled user reports.
- Estimate unit economics for 100, 1,000 and 10,000 subscribers and for several device participation rates. Include API, VPN egress, compute verification, rewards, support, payment, tax, fraud and churn.
- Review Russian legal and platform requirements for VPN distribution, crypto/token rewards, consumer subscriptions, payment processing, advertising and personal data before public launch.

## 7. Sources and evidence log

### Primary pricing / product sources
- Yandex AI Studio pricing (official; Russian ruble prices, VAT included, cache/tool/async distinctions): https://aistudio.yandex.ru/ru/docs/ai-studio/pricing
- xAI API pricing (official; Grok model token rates, long-context, cached, priority and regional pricing): https://docs.x.ai/developers/pricing
- xAI Grok 4.7 model page (official; 500k context and token rates): https://docs.x.ai/developers/models/grok-4.7
- xAI consumer pricing (official): https://x.ai/pricing
- X Subscription announcement (official; $30/$100/$200 tiers): https://x.ai/news/x-subscription
- X Premium plan help (official; regional/local prices vary): https://help.x.com/en/using-x/x-premium
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
