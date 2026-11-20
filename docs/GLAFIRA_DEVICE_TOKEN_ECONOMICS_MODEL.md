# GLAFIRA — device contribution and token economics model

**Status:** calculation framework for benchmarking; not an approved issuance schedule or a promise of earnings.  
**Version:** 0.1-draft · 2026-11-20

## 1. Purpose

Estimate, for a concrete phone or PC model, how much verified GLAFIRA compute contribution it may produce and whether the earned internal utility tokens could cover a chosen VPN plan and AI usage.

Keep three quantities separate: verified contribution, token reward, and service coverage. Tokens are not cash, guaranteed income, provider API credits or a promise that the user can recover device electricity costs.

## 2. Device input model

One row per device model and workload class.

| Input | Symbol | Unit | Source / rule |
|---|---:|---|---|
| Standardized benchmark throughput | B | accepted-work units/hour at 100% reference allocation | measured on actual device/workload |
| Contribution share | S | 0–1 | smartphone ceiling 0.10; Windows PC owner-configured 0.20–1.00 only while idle and yielding to owner activity |
| Measured utilization while eligible | U | 0–1 | runtime telemetry, not requested utilization |
| Eligible contribution time | H | hours | actual time while all policies allow work |
| Accepted-work ratio | A | 0–1 | accepted units / submitted units; rejected, duplicate and unverifiable work earns zero |
| Quality multiplier | Q | policy-bounded multiplier | verifier result, task class and policy version |
| Reliability multiplier | R | policy-bounded multiplier | lease completion, valid heartbeat and failure history |
| Reward rate | K | GLF / work unit | centrally approved, versioned issuance parameter |
| Average incremental power | P | watts | measured at wall or battery telemetry, where available |
| Electricity price | E | currency / kWh | entered by user; never assumed |
| Network transfer | N | MB/hour | measured; smartphones use Wi-Fi only, never cellular |
| Metered network price | D | currency / GB | entered by user |

Do not infer throughput from CPU/GPU marketing names alone. Store benchmark result, OS, thermal state, memory pressure, power profile, date and workload version with each measurement.

## 3. Core formulas

For one device, workload class and measurement period:

**Eligible useful work**
\[
WU = B \times S \times U \times H \times A
\]

**Gross token reward**
\[
GLF_{gross} = \min(GLF_{task\_cap},\; WU \times K \times Q \times R)
\]

Mother applies task, device, account and project budget caps. A task is rewarded only once after acceptance. Assignment, online status, raw uptime and unverified output do not create compute rewards.

**Average token earning rate**
\[
GLF_{hour} = \frac{GLF_{gross}}{H}
\]

If H is zero, the rate is undefined.

**Energy use and cost**
\[
kWh = \frac{P \times H}{1000}
\]
\[
Cost_{energy} = kWh \times E
\]

**Network transfer and possible metered cost**
\[
GB = \frac{N \times H}{1024}
\]
\[
Cost_{network} = GB \times D
\]

**Indicative operating cost**
\[
Cost_{operating} = Cost_{energy} + Cost_{network} + Cost_{other}
\]

Cost_other may include measured incremental cooling, battery wear or other owner-entered costs. Do not invent these values.

## 4. VPN and AI coverage

Let V_day be the current daily VPN tariff in GLF, V_month the current monthly VPN tariff in GLF, and AI_i the current tariff for one request of model/modality i.

\[
VPN\ days = \frac{GLF_{period}}{V_{day}}
\]

\[
VPN\ months = \frac{GLF_{period}}{V_{month}}
\]

\[
AI\ requests_i = \frac{GLF_{period}}{AI_i}
\]

These are alternative uses of the same balance, not additive entitlements. To cover both VPN and AI:

\[
Required_{period} = V_{month} + \sum_i (Requests_i \times AI_i)
\]

\[
Coverage = \frac{GLF_{period}}{Required_{period}}
\]

Coverage >= 1 means the modeled reward covers the selected internal service tariffs for that period. It does not prove cash profitability.

## 5. Service-equivalent value versus cash economics

Display two separate outcomes:

- **Service-equivalent coverage:** how many VPN days/months or AI requests earned tokens can buy at published GLAFIRA tariffs.
- **Cash operating cost:** measured electricity and any metered network cost in the user's currency.

Do not subtract electricity currency directly from GLF unless the owner has approved a defensible internal reference conversion. Do not display a token balance as cash. Any future reference price per token must be labeled non-cash and include tariff version/date.

## 6. Device benchmark matrix

The first calibration set should include actual devices, not generic processor tiers.

| Device | CPU / SoC | GPU / NPU | OS | Workload class | B (WU/h) | P (W) | Thermal notes | Sample count |
|---|---|---|---|---|---:|---:|---|---:|
| Android phone A | measure | measure | Android version | CPU inference / validation | TBD | TBD | TBD | TBD |
| Android phone B | measure | measure | Android version | CPU inference / validation | TBD | TBD | TBD | TBD |
| Windows PC A | measure | measure | Windows version | CPU / GPU / browser / validation | TBD | TBD | TBD | TBD |
| Windows PC B | measure | measure | Windows version | CPU / GPU / browser / validation | TBD | TBD | TBD | TBD |

For each row run: idle baseline; 15-minute warm-up; sustained 60-minute workload; thermal-throttle and owner-activity interruption; accepted and deliberately rejected result cases; network-offline and reconnect cases. Use median and p95; preserve raw measurements and benchmark version.

## 7. Anti-abuse and fairness controls

- Sign each task offer and bind it to device, lease, fencing token, task type, budget and expiry.
- Verify result integrity and task-specific correctness before issuance.
- Deduplicate by task ID and idempotency key; one accepted result, one reward.
- Cap rewards by task, device, account, project and issuance period.
- Detect duplicate device identities, replay, collusion, synthetic work and abnormal acceptance patterns.
- Do not reward arbitrary online time or self-reported benchmark scores.
- Let users pause contribution immediately and see task, resource, energy/network estimates and reward receipts.
- Reward useful accepted work, not brand names.

## 8. Calibration and approval gates

1. Implement telemetry and benchmark harness before setting K.
2. Measure representative Android and Windows devices across at least three workload classes.
3. Estimate actual VPN infrastructure, AI/provider, storage and egress costs.
4. Define internal service tariffs and issuance budget independently.
5. Simulate low, median and high device performance and adversarial acceptance ratios.
6. Verify expected issuance does not exceed funded service capacity and project budgets.
7. Publish formula, parameter version, examples, limits and uncertainty range.
8. Obtain owner approval and legal/accounting review before public reward claims.

**Current decision:** no fixed GLF-per-hour figure, earnings promise, cash value, or final K/Q/R coefficients are approved. This is a transparent framework for collecting the measurements needed to make that decision.
