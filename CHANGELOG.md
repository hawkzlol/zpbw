# Changelog

## 1.0.3 - 2026-11-22

### Minecraft versions

- Support Minecraft 26.2 and 26.3 with separate native JARs from one shared codebase.
- Preserve each version's native teleport responses, input dispatch and interaction behavior.
- Update notifications select the correct Minecraft artifact and require a complete native release pair.

### Prediction and input

- Add `/zpbw chainmax [value]`, clamped to 1–5 (default 5). Saving a new limit preserves pending warps and their deadlines.
- Preserve native position reminders during chaining and the separate server-driven rotation response.
- Improve grazing-edge ray accuracy using double-precision direction math; add bounded mismatch diagnostics.
- Keep NSD's required early-input commitment while sampling current keys afterwards, without stale input replay.

### Recovery and usability

- Recover automatically on the same connection after genuine authority and fresh input settle; no reconnect is required.
- On failure, discard obsolete speculative actions and resume fresh native gameplay. Keep late genuine teleport handling intact.
- Restart recovery stabilization after each native use. Give issued warps a natural-client-tick minimum grace alongside server Ping counting.
- Quietly drop obsolete queued mining continuations without clearing later independent clicks; clarify cancellation messages.
- Fix disconnect cleanup ownership, clipboard newline handling and console fallback when copying logs fails.

### Known limitations

- An intermittent falling 1.414-block prediction discrepancy remains under investigation. Run `/zpbw logs` immediately after a failure.
- Local simulation checks do not establish every remote server's behavior. See [validation notes](docs/validation-1.0.3.md).

## 1.0.2 - 2026-11-17

### NoSneakDelay

- Use current keys at the next uncommitted native sample instead of replaying stale movement, jump or sprint input.
- Preserve early sneak, immediate sneak-use behavior and brief taps. The first sample required by an already-transmitted input remains committed; this does not remove its movement delay.

### Chaining and recovery

- Reuse each pending slot as its genuine confirmation arrives, keeping successful chains moving without waiting for the whole chain to drain.
- Queue up to five additional warp clicks when the five prediction slots are full; dispatch at most one queued click per native input tick.
- Keep mining, entity clicks and other world interactions behind the movement and source tick they depend on.
- Fix failed chains resuming speculative movement before their remaining genuine teleports arrive. A bounded recovery pause drains those responses, then resumes from fresh native input.
- Invalidate pending speculation when a real server knockback or explosion changes player motion. Preserve the native impulse if no teleport follows, and let genuine teleports supersede it.
- Preserve native teleport acknowledgements, inventory synchronization, click cleanup and world/session/ownership boundaries throughout recovery.

### Verification and limits

- 237 automated mod tests pass. Scoped runtime results and remaining limits are recorded in `docs/validation-1.0.2.md`.
- Official release approved with the documented limitations. Scoped offline qualification is complete. Known baseline attribute synchronization, raw-Ping timeout behavior and delayed-server semantics are documented in `docs/validation-1.0.2.md`.
- The five-pending limit and nonzero-Ping timeout remain. Bursty server traffic at high latency can still expire predictions; recovery is distinct from successful continuous prediction.
- Failure recovery can briefly pause movement and dependent actions for up to a separate 20 nonzero server Ping packets. Successful chaining is unchanged by that barrier.
- No extra gameplay packets, simulation ticks or TickEnd packets are introduced by these recovery fixes. No anti-cheat checks or production focus/GUI protections are weakened.

## 1.0.1 â€” 2026-10-07

### Fixes

#### Mining and interactions

- Keep queued mining and its swing together after warp confirmation, with physics using the updated block state.
- Cancel mining against changed or missing blocks and recheck the target before replay.
- Prevent held mining from accumulating multiple progress updates in one tick; clear queued mining on world/connection changes.

#### Prediction and recovery

- Fix first-warp predictions when sneak was pressed but had not yet been sent to the server. NoSneakDelay remains supported.
- Discard obsolete movement/input ticks after a failed prediction while preserving inventory synchronization.
- Refresh the interaction target after rollback and restore input, sprint and hotbar tracking when buffered actions are cancelled.
- Add dispatched use angles and movement context to `/zpbw logs` for diagnosing airborne prediction mismatches.

#### Standalone SkyBlock detection

- Remove the optional Blackwell lookup and developer override dependency.
- Read full sidebar text, tab headers/footers and rendered player names directly from Minecraft.
- Handle formatted text and Unicode spacing; ignore empty or decorative area labels.

### Verification and remaining limits

- 202 automated mod tests pass. Gameplay fixes were exercised in the owned Grim lab at 25â€“300 ms latency, including mining, interactions, recovery, jumps and Jump Boost V.
- The new detector passed automated checks and mapping verification; a minimal-client/live SkyBlock runtime check has not been completed.
- Five-warp chaining still pauses new dispatch after the first confirmation until the remaining chain settles. Timeout still counts nonzero server Ping packets; bursts can exhaust its budget. General airborne prediction mismatches remain under investigation.

## 1.0.0 â€” 2026-10-06

- Initial public release of Zero Ping Blinkwarp for Minecraft 26.2.
- Local landing prediction with ordered packet holding, genuine teleport confirmation, and up to five overlapping warps.
- Optional NoSneakDelay, configurable confirmation timeout, clipboard diagnostics, and session update notifications.
