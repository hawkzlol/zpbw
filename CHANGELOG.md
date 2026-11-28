# Changelog

## 1.0.5-pre1 (prerelease)

- Overlay’s **Show Always** uses crouching eye height, even while standing.
- Adjusted **Smooth Landings** to reduce the small vertical camera dip after Blinkwarp.
- Added experimental prediction changes, enabled by default. You can turn them off in General.

## 1.0.4 — 2026-11-24

- Open the settings menu with `/zpbw`. It remembers the last tab and has an optimized Blinkwarp preset and help for chain maximum, timeout, and sounds on failed guesses.
- Add a customizable Etherwarp overlay, sound previews and replacement sounds, Smooth Landings, and chat controls. The overlay draws above ordinary waypoints when Depth is off.
- Resume Blinkwarp after server confirmation and the next native input boundary. Continued clicks no longer restart the old recovery wait. Debug reports retain more context for different kinds of issues.
- Include experimental server lag compensation, enabled by default, and a setting that pauses prediction in restricted areas.

Rare snapbacks remain possible during rapid chained Etherwarps. If one occurs, use `/zpbw logs` promptly and include the report when filing an issue.

## 1.0.4-pre2 (prerelease)

- Add **General > Server Lag Compensation (Experimental)**, enabled by default, to attempt to prevent kicks during server stalls on both native versions. Explicit saved OFF choices are preserved.
- Abandon stale held movement while preserving genuine native teleport responses and issued FIFO ownership. New native controls continue; prediction resumes after synchronization.
- Use the actual player origin for the overlay during native fallback. The normal configurable timeout remains unchanged.
- Include trigger reasons, Ping gaps, hold ages, thresholds and recovery state in `/zpbw logs`.
- Notify about newer prereleases in orange, with an optional-but-recommended update message and the matching native download.
- Chore: The Prediction Engine needs work. If you can substantially improve it, contact **zhxwks** on Discord (message request preferred).

Known issue: rare snapbacks can still occur after rapid chained Etherwarps. This experimental mitigation has scoped local verification; the exact remote kick is not verified fixed.

## 1.0.4-pre1 (previous prerelease)

The original release retains its `v1.0.4` tag, download URLs and binaries, which identify as `1.0.4`.

- Open the ZPBW settings menu with `/zpbw` on Minecraft 26.2 and 26.3. Use `/zpbw help` for the command list.
- Configure Blinkwarp, chain maximum, timeout and NoSneakDelay in the menu, and export debug logs from its footer.
- Add optional Smooth Landings (Visual) to soften the small vertical camera hop after a Blinkwarp.
- Keep the menu size and click targets independent of Minecraft's GUI Scale setting.
- Add links to the GitHub repository and Issues in General, and an extra clipboard fallback for Windows and Linux desktops with X11 access.
- Add Reduced Chat Logs and Disable Chat Logs settings. Debug exports remain available in either mode.
- Pause new Blinkwarp predictions in restricted puzzles, boss phases and other areas by default. General can turn this safeguard off.
- Add an Overlay page with next-target prediction during pending warps, a visual color picker with hex and opacity controls, and configurable outline width, depth and block shape.
- Add a separate Sounds page with searchable Minecraft sounds, per-sound previews, volume and pitch controls, and a choice of Blinkwarp or server Etherwarp timing.
- Let the overlay appear without sneaking and optionally replace matching server sounds when Blinkwarp did not play a local cue.
- Fix native text entry on 26.3 and align labels with their descriptions.

Rare snapbacks can still occur after rapid chained Etherwarps. If it happens, run `/zpbw logs` immediately and include the report when filing an issue.

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
