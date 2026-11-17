# 1.0.2 validation notes

**Released by maintainer decision on 2026-11-17 with the limitations below.** The failure-only recovery repair is implemented and frozen. Scoped comparisons are complete, including the isolated offline continuation. Quiet captures and compilation are not an anti-cheat acceptance guarantee.

## Frozen artifact

- SHA256: `99048EA44E4B4D375F112FAF2F4E0AF9FA3CB6A19F0E5E80148466D5B8194C78`.
- Minecraft 26.2, Fabric, Java 25; 237 automated mod tests pass.
- All 95 production classes match the frozen bench candidate; ten NSD classes equal the minimal input-latch repair candidate. Private harness classes are absent. Earlier candidates are preserved.
- No production code or candidate binary changed during the offline continuation. The exact frozen binary was installed for maintainer Hypixel testing on 2026-11-17 and selected unchanged for this release. No additional live Hypixel result is claimed.

## Runtime scope

Owned tests retain installed Grim 2.3.74 checks/configuration. Native bindings/callbacks are exercised at 60/200 FPS, requested latency stages 25/50/100/200/300 ms, and separate jitter/fragmentation controls. Actual Ping/Pong timings are retained; requested latency is not an exact timing guarantee. Physical OS input and full production-modpack coexistence are separate checks.

A coarse Windows asyncio clock allowed an older relay to admit bytes early. Old captures retain measured timings and count as nominal stages. The repaired high-resolution byte-FIFO relay passes 17 socket/deadline tests and does not parse or construct Minecraft packets.

After external authentication failures, testing continued in a separately authorized loopback offline lane with fresh identity/world/database state. The authenticated lab remained unchanged. All 14 plugins were retained; Grim JAR/configuration and player permission snapshots match the source. Private adapters are restricted to exact offline paths/ports and absent from the production JAR. An unrelated map-import job was paused in that clone; this is arena-fixture qualification, not full dungeon-map validation.

## Verified effects and limits

The exact standalone's earlier repaired-clock first-warp matrix has 100 actual server teleports,90 enabled settlements and zero flags/setbacks/fallbacks. It covers airborne/Jump Boost V cases with click/use aim checked. Another 45 native pearl effects are independently verified.

The offline continuation completed 114 main matrix windows with **zero Simulation flags and zero setbacks across all lifecycle phases**. Its 38 DuplicateRotPlace callbacks remain recorded; this is not 114 blanket functional passes:

- Native levers: 30 actual toggles across all five latency stages,15 enabled settlements, no fallbacks/guard blocks.29 DuplicateRotPlace callbacks.
- Both-off levers: 10 actual toggles with both features verified disabled; nine DuplicateRotPlace reproduce the warning without either feature enabled. The mod/private harness remain loaded; pristine no-mod proof is not claimed.
- Recovery: 20 cases at 50/300 ms and 60 FPS, no flags/setbacks. Four denied cases prove the original received impulse resumes unchanged; empty-force explosions preserve both predictions; external ownership rejects while occupied, acquires after drain and releases. Input-edge cases dispatch two uses at 50 ms and five at 300 ms, not universal five-way overlap.
- NSD edges: 30 cases at 25/100/300 ms with jitter,33 settlements, no flags/setbacks/fallbacks/guard blocks. Rapid taps, immediate press/use, movement/jump/sprint, GUI/focus and toggles exercised. Three native merchant menus and three NPC health reductions verified. Six blocked GUI/focus windows suppress early sneak entry before sampling.120 binding events and 63 writes before native sample are local observations, not OS latency measurements.
- Rolling chains: 20 windows with no flags/setbacks. Enabled 12/12/40 streams fully settle at 25/50/100 ms. At200/300 ms seven literal raw-Ping timeouts interrupt successful prediction. Recovery is not continuous chaining success.
- Pascal delayed model: four windows with no flags/setbacks, 98 actual executions at requested 50 ms (elapsed 50–102 ms), but six position-mismatch fallbacks. The installed plugin freezes destinations before queueing; a second use can target from the old server position before the first teleport executes. This differs from sequential delayed execution. It does not establish Hypixel's one-tick semantics or qualify successful prediction under that model.

All matrices observe at most one input packet between natural TickEnds. Focus-negative probes use a private controlled native callback; the production JAR has no lab bypass. Deliberate negative cases, failed controls and incomplete setup attempts are retained separately.

## Raw Ping cadence

The native heartbeat's20-Hz job does not limit total nonzero Pings: Grim's additional genuine transactions remain. One200ms chain case exhausts 20 received Pings after 155.898 ms, before its matching 204.931 ms reply; server-side observations independently confirm 33 unique Pings/native Pongs within 200 ms. These use separate same-clock timelines, never cross-JVM subtraction.

No Ping filtering, wall-clock fallback, budget increase, extra TickEnd or weakened synchronization was added. Real Hypixel high-latency chain behavior and its actual Ping cadence remain manual checks.

## Prior Simulation baseline

An earlier enabled first-step speed-transition fault reproduced with NSD alone, then with both features disabled. In the fully traced both-off case, the client requests a native Pong and computes/sends movement before the replacement movement-speed attribute has reached Netty. Grim has already advanced its model on that preceding acknowledgement. Installed attribute replication sends its transaction before the self attribute update.

The both-off control still loads this mod/private harness; a separate pristine no-mod client is not claimed. Original enabled/NSD-only signatures match, but only the later control has the full causal trace. This does not classify every Simulation warning or erase earlier real client bugs that were repaired. The new quiet offline matrices do not erase the earlier failure. The maintainer explicitly chose to publish this version with these limitations documented.

No guessed future attribute, delayed heartbeat, ordinary movement suppression, correction suppression or check change was made. The maintainer approved publication with that attributed baseline fault documented. Its independent resolution and further Hypixel/modpack checks remain follow-up work; neither is claimed complete.

## Preserved ordering

Failure recovery drains genuine native responses or a separate 20-nonzero-Ping budget, then resumes dependent actions behind fresh native input and a natural transmitted TickEnd. Successful rolling slot reuse remains unchanged. Native impulses survive when no teleport supersedes them. No extra gameplay packet, simulation tick or TickEnd is added.

The first sample required by an already-transmitted NSD input remains committed. The repair removes stale deferred replay, not its first-sample movement delay. Current Hypixel head-rotation behavior and physical input timing remain separate manual checks.

All offline processes/listeners stopped cleanly; recovery journals are clear. Original authenticated plugin/profile hashes and auto-start definition/state are verified unchanged. Prior mining-control failures and death resync callbacks remain documented within their original scope, not retrospectively called clean.
