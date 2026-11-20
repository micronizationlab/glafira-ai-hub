# GLAFIRA — device contribution and token economics model

**Status:** calculation framework for benchmarking; not an approved issuance schedule or a promise of earnings.  
**Version:** 0.1-draft · 2026-11-20

## 1. Purpose

Estimate, for a concrete phone or PC model, how much verified GLAFIRA compute contribution it may produce and whether the earned internal utility tokens could cover a chosen VPN plan and AI usage.

The calculator must keep three different quantities separate:

1. **Verified contribution** — useful work accepted by the scheduler/verifier.
2. **Token reward** — internal units credited by Mother under a versioned reward policy.
3. **Service coverage** — the amount of GLAFIRA VPN/AI services that those tokens can purchase at the current tariff.

Tokens are not cash, guaranteed income, provider API credits or a promise that the user can recover the device's electricity cost.

## 2. Device input model

One row per device model and workload class.

| Input | Symbol | Unit | Source / rule |
|---|---:|---|---|
| Standardized benchmark throughput | B | accepted-work units/hour at 100% reference allocation | measured on the actual device and workload |
| Contribution share | S | 0–1 | smartphone ceiling 0.10; Windows PC owner-configured 0.20–1.00 only while idle and yielding to owner activity |
| Measured utilization while eligible | U | 0–1 | runtime telemetry, not requested utilization |
| Eligible contribution time | H | hours | actual time while all owner/device policies allow work |
| Accepted-work ratio | A | 0–1 | accepted units / submitted units; rejected, duplicate and unverifiable work earns zero |
| Quality multiplier | Q | policy-bounded multiplier | verifier result, task class and policy version |
| Reliability multiplier | R | policy-bounded multiplier | lease completion, valid heartbeat and failure history |
| Reward rate | K | GLF / work unit | centrally approved versioned issuance parameter |
| Average incremental power | P | watts | measured at the wall or battery telemetry, where available |
| Electricity price | E | currency / kWh | entered by the user; never assumed |
| Network transfer | N | MB/hour | measured; contribution on smartphones is Wi-Fi only, never cellular |
| Metered network price | D | currency / GB | zero only when the connection is genuinely unmetered or included |

Do not infer throughput from a CPU/GPU marketing name alone. Keep a benchmark result, OS, thermal state, memory pressure, power profile, date and workload version beside each measurement.

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

The task cap, per-device cap, per-account cap and project budget are applied by Mother. A task is rewarded only once after acceptance. Assignment, online status, raw uptime and unverified output do not create compute rewards.

**Average token earning rate**
\[
GLF_{hour} = \frac{GLF_{gross}}{H}
\]

If H is zero, the rate is undefined, not zero-cost free capacity.

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

Let:

- V_day = current daily VPN tariff in GLF;
- V_month = current monthly VPN tariff in GLF;
- AI_i = current tariff for one request of model/modality i;
- GLF_period = verified tokens earned over the same period.

Then:

\[
VPN\ days = \frac{GLF_{period}}{V_{day}}
\]

\[
VPN\ months = \frac{GLF_{period}}{V_{month}}
\]

\[
AI\ requests_i = \frac{GLF_{period}}{AI_i}
\]

These are alternative uses of the same balance, not additive entitlements. If the owner wants to cover both VPN and AI, calculate:

\[
Required_{period} = V_{month} + \sum_i (Requests_i \times AI_i)
\]

\[
Coverage = \frac{GLF_{period}}{Required_{period}}
\]

Coverage >= 1 means the modeled token reward covers the selected internal service tariff for that period. It does not prove cash profitability.

## 5. Service-equivalent value versus cash economics

The model must display two separate outcomes:

- **Service-equivalent coverage:** how many VPN days/months or AI requests the earned tokens can buy at the published GLAFIRA tariffs.
- **Cash operating cost:** measured electricity and any metered network cost in the user's currency.

Do not subtract electricity currency directly from GLF unless the owner has approved and published a defensible internal reference conversion. Do not display a token balance as a cash amount. If a reference service-equivalent price per token is later approved, show it as an explicitly non-cash comparison, with the tariff version and date.

## 6. Worked calculation template (illustrative variables only)

The following is deliberately not populated with guessed hardware throughput, token rates, electricity prices or VPN tariffs.

| Metric | Formula | Result |
|---|---|---:|
| Eligible work units | B × S × U × H × A | input-driven |
| Earned GLF | min(cap, WU × K × Q × R) | input-driven |
| GLF per eligible hour | earned GLF / H | input-driven |
| Energy kWh | P × H / 1000 | input-driven |
| Energy cash cost | kWh × E | input-driven |
| VPN days | earned GLF / V_day | input-driven |
| VPN months | earned GLF / V_month | input-driven |
| AI requests | earned GLF / AI_i | input-driven |
| Combined service coverage | earned GLF / required GLF | input-driven |

## 7. Device benchmark matrix

The first calibration set should include actual devices, not generic processor tiers:

| Device | CPU / SoC | GPU / NPU | OS | Workload class | B (WU/h) | P (W) | Thermal notes | Sample count |
|---|---|---|---|---|---:|---:|---|---:|
| Android phone A | measure | measure | Android version | CPU inference / validation | TBD | TBD | TBD | TBD |
| Android phone B | measure | measure | Android version | CPU inference / validation | TBD | TBD | TBD | TBD |
| Windows PC A | measure | measure | Windows version | CPU / GPU / browser / validation | TBD | TBD | TBD | TBD |
| Windows PC B | measure | measure | Windows version | CPU / GPU / browser / validation | TBD | TBD | TBD | TBD |

For each row, run at least:
- idle baseline with GLAFIRA stopped;
- controlled 15-minute warm-up;
- sustained 60-minute workload;
- thermal-throttle and owner-activity interruption;
- accepted and deliberately rejected result cases;
- network-offline and reconnect cases.

Use median and p95 results; preserve raw measurements and benchmark version.

## 8. Anti-abuse and fairness controls

- Sign each task offer and bind it to device, lease, fencing token, task type, budget and expiry.
- Verify result integrity and task-specific correctness before issuance.
- Deduplicate by task ID and idempotency key; one accepted result, one reward.
- Cap rewards by task, device, account, project and issuance period.
- Detect duplicate device identities, replay, collusion, synthetic work and abnormal acceptance patterns.
- Do not reward arbitrary online time or self-reported benchmark scores.
- Allow the user to pause contribution immediately and see task, resource, energy/network estimates and reward receipts.
- A lower-power device must not be disadvantaged solely by model marketing tier; reward useful accepted work, not brand names.

## 9. Calibration and approval gates

1. Implement telemetry and benchmark harness before setting K.
2. Measure representative Android and Windows devices across at least three workload classes.
3. Estimate real VPN infrastructure cost, AI/provider cost, storage, egress and reserve.
4. Define internal service tariffs and issuance budget independently.
5. Simulate low, median and high device performance and adversarial acceptance ratios.
6. Check that expected issuance does not exceed funded service capacity and project budgets.
7. Publish the formula, parameter version, examples, limits and uncertainty range.
8. Obtain owner approval and legal/accounting review before public reward claims.

**Current decision:** no fixed GLF-per-hour figure, earnings promise, cash value, or final K/Q/R coefficients are approved. The formula is a transparent framework for collecting the measurements needed to make that decision.
