# Changelog

## 1.0.1 — 2026-10-07

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

- 202 automated mod tests pass. Gameplay fixes were exercised in the owned Grim lab at 25–300 ms latency, including mining, interactions, recovery, jumps and Jump Boost V.
- The new detector passed automated checks and mapping verification; a minimal-client/live SkyBlock runtime check has not been completed.
- Five-warp chaining still pauses new dispatch after the first confirmation until the remaining chain settles. Timeout still counts nonzero server Ping packets; bursts can exhaust its budget. General airborne prediction mismatches remain under investigation.

## 1.0.0 — 2026-10-06

- Initial public release of Zero Ping Blinkwarp for Minecraft 26.2.
- Local landing prediction with ordered packet holding, genuine teleport confirmation, and up to five overlapping warps.
- Optional NoSneakDelay, configurable confirmation timeout, clipboard diagnostics, and session update notifications.
