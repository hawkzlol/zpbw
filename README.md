# Zero Ping Blinkwarp!

Immediately teleports you to your targeted block when you attempt an etherwarp.

Zero Ping Blinkwarp predicts the result of your etherwarp locally and moves you there immediately, without waiting for the server's teleport response to make the full round trip first.

Unlike older zero ping implementations which effectively left you limited to camera movement while waiting for the real teleport to arrive, Blinkwarp allows you to continue moving, *interact*, click entities, use items, change slots, swing, and generally continue playing normally. Anything that cannot safely be sent yet is held temporarily and replayed in the correct order once the real server teleport arrives.

The goal is not to fake the server teleport or replace Minecraft's normal teleport handling. The real etherwarp use is still sent to the server, the server still performs its own raycast, and the genuine teleport response is still processed normally. Blinkwarp simply predicts the result ahead of time and keeps your client responsive while waiting for that confirmation.

The mod has been tested in a local server lab across different latencies, chained warps, interactions and recovery cases. That does not cover every server or combination of mods. It will remain actively maintained until this game no longer interests me, or I consider the mod to be in a strong enough state to leave as-is.

Use `/zpbw` to open settings or `/zpbw help` to see the available commands.

## Installation

Download **[ZPBW 1.0.3](https://github.com/hawkzlol/zpbw/releases/tag/v1.0.3)** from GitHub Releases.

ZPBW is a client-side Fabric mod for **Minecraft Java 26.2 and 26.3**, requiring **Java 25**, **Fabric API** and **Fabric Language Kotlin 1.13.13+kotlin.2.4.10 or newer**.

Choose exactly one matching JAR:

| Minecraft | Download | Minimum Fabric Loader | Minimum Fabric API |
| --- | --- | --- | --- |
| 26.2 | `zpbw-mc26.2-1.0.3.jar` | 0.19.3 | 0.155.2+26.2 |
| 26.3 | `zpbw-mc26.3-1.0.3.jar` | 0.19.5 | 0.165.0+26.3 |

Put the matching JAR and dependencies in `mods`, removing the previous standalone ZPBW JAR. Keep your configuration. Prediction and NoSneakDelay default to off on a fresh installation. Blackwell is not required.

## Commands

Every setting below is saved between sessions.

| Command | What it does |
| --- | --- |
| `/zpbw` | Opens the settings menu. |
| `/zpbw help` | Shows the command list. |
| `/zpbw on` / `/zpbw off` | Enables or disables prediction. |
| `/zpbw nosneakdelay` or `/zpbw nsd` | Toggles NoSneakDelay. Add `on` or `off` to set it explicitly. |
| `/zpbw timeout <ticks>` | Sets the timeout for new warps. Values are clamped to 2–20; the default is 20. |
| `/zpbw chainmax [value]` | Shows the overlap limit, or saves a signed integer clamped to 1–5 (default 5). |
| `/zpbw logs` | Copies session troubleshooting information to your clipboard, or prints it to the game console if clipboard access fails. |

Under General, **Reduced Chat Logs** shows a short message for destination mismatches. **Disable Chat Logs** mutes all automatic ZPBW chat messages and takes precedence over reduced mode. Command replies and debug exports remain available.

**Disable in Puzzles, Bosses & Similar** is enabled by default. It pauses new Blinkwarp predictions in Boulder, Teleport Maze, trap rooms, Maxor's phase, Glacite Mineshafts, Jungle Temple, Dojo and guest islands.

**Overlay** highlights the landing block for an armed Etherwarp item. Enable **Show Always** to preview while holding an eligible item without sneaking. While a Blinkwarp is pending, the highlight previews the next jump from the predicted landing. Set the colors with the visual picker or enter `#RRGGBB` or `#RRGGBBAA`. You can also adjust opacity, outline width, depth, and block shape.

**Sounds** lets you search and preview Minecraft sounds and adjust their volume and pitch. **Blinkwarp** plays the selected sound when the local prediction takes effect. **Play Sound on Failed Guesses.** also replaces a matching nearby server cue when no local sound played. **Etherwarp** waits for the server's matching sound.

NoSneakDelay attempts to send sneak input earlier while preserving packet order. An early input already sent to the server must remain committed through its first natural input tick; movement-key changes during that interval can wait until the following sample. Once the commitment ends, current keys are sampled directly without replaying an older deferred snapshot.

Timeouts count every nonzero server Ping packet and require a minimum number of completed natural client ticks after a warp is issued. Waiting clicks retain their original admission deadline and Ping anchor. Once a queued warp is issued, its minimum response grace starts then, so waiting cannot consume that grace. No fixed tick duration, extra ticks or extra packets are introduced.

## Update notifications

ZPBW checks this repository's latest stable GitHub release once per client session, in the background. If a newer version contains the complete pair of exact native install artifacts, a notification appears a few seconds after you join SkyBlock, like the first-install welcome. It does not download or install updates.

No release, an inaccessible repository or a failed request simply means no update notification. Pre-releases are not offered by this checker.

SkyBlock detection reads Minecraft's sidebar and tab list directly. It does not require another SkyBlock mod or inherit another mod's developer overrides.

## Reporting a problem

Run `/zpbw logs` after the problem occurs, **before closing Minecraft**, and include the copied text in a [bug report](https://github.com/hawkzlol/zpbw/issues/new?template=bug_report.yml). Explain what you clicked, what you expected and what happened instead. A short clip can help with movement or interaction problems.

Diagnostics are kept in a bounded in-memory buffer for the current launch; ZPBW does not continuously write diagnostic files. The report includes relevant mod versions, settings and recent prediction events, including coordinates. It excludes chat, account details, server addresses and raw packet payloads. Review the copied text before posting it publicly.

## Building from source

With Java 25 installed:

```sh
python tools/verify_dual_native.py
```

This tree builds 1.0.4 independently for released Java 26.2 and 26.3 using Java 25. The command cleans, tests, builds and validates both native roots, then collects a matching immutable pair under `artifacts/native-pairs/<run-id>/`. Install only the JAR matching your exact Minecraft version: `zpbw-mc26.2-1.0.4.jar` or `zpbw-mc26.3-1.0.4.jar`. A collected build is **BUILT_UNQUALIFIED**, not runtime-qualified or released. See [native build and validation](docs/dual-native-1.0.3.md).

See [validation and known limitations](docs/validation-1.0.3.md) for the release checks. Compilation alone is not functional verification.

# How it works, and why.

Normally, when you etherwarp, your client sends a `ServerboundUseItemPacket` to the server.

The server receives that use, performs the etherwarp raycast, determines where you should land, and sends a `ClientboundPlayerPositionPacket` back to your client containing the resulting teleport.

Only after that packet reaches you does your client actually get placed at the destination.

That means the visible result of an etherwarp normally has to wait for a full client -> server -> client round trip. The higher your latency is, the more noticeable that delay becomes.

Zero Ping Blinkwarp removes that delay from your side of the interaction.

When you attempt an etherwarp, Blinkwarp predicts the destination locally. If a valid destination is found, the real use is sent to the server first. Blinkwarp then relocates your client during the natural source tick, without waiting for the response. Prediction uses the world state available to your client; it is not a guarantee that the server's raycast will produce the same result.

The important difference is what happens after that.

Instead of freezing you in place or only allowing camera movement until the server catches up, Blinkwarp lets your local game continue normally. Movement, clicks, interactions, swings, slot changes and other relevant actions are kept in an ordered queue when they cannot safely be sent yet.

The flow is roughly:

**Use Item -> Predict Destination -> Send Real Use -> Natural Source Tick and Local Teleport -> Hold Dependent Actions -> Receive Real Teleport -> Verify Prediction -> Replay Held Actions**

Once the server's genuine teleport arrives, Blinkwarp checks the authoritative landing position against the position it predicted.

If they match, Minecraft's normal teleport handler is allowed to finish the teleport exactly as it normally would, including its native teleport acknowledgement and movement response. Blinkwarp then releases the actions it had been holding, in their original order.

This means the server still remains authoritative. Blinkwarp does not invent a teleport ID, send a guessed acknowledgement, or pretend that the server has already confirmed something it has not.

<img width="1254" height="1254" alt="image" src="https://github.com/user-attachments/assets/e058340f-ff7c-4142-8409-5c3edff01c1a" />

# Why the packet holding is necessary.

Immediately moving the player locally is the easy part.

The difficult part is everything you do between the prediction and the real server confirmation.

If those packets were simply sent from your predicted position immediately, the server would receive movement and interactions based on a teleport it has not processed yet. Apart from being incorrect packet ordering, this can cause interactions to happen from the wrong position, movement to arrive out of sequence, or server-side checks to reject what the client is doing.

Blinkwarp instead keeps those actions behind the pending teleport.

The server first receives the real etherwarp use and processes the real teleport. Once that teleport comes back and is verified, the held actions are allowed through in the same order they originally happened.

For some interactions Blinkwarp goes further than simply holding the finished network packet. The original Minecraft action itself is deferred, so things such as interaction sequences, selected slots, swings, entity targets and other vanilla behaviour are created at the correct point in the replay rather than being generated early and blindly sent later.

This is what allows Blinkwarp to remain responsive without turning the connection into a pile of incorrectly ordered packets.

# Chaining

Blinkwarp also supports chaining multiple etherwarps without waiting for each previous teleport to finish its round trip first.

A new warp can be predicted from the previous predicted landing position, allowing another etherwarp to happen immediately while earlier server confirmations are still in flight.

Intermediate movement that the server could not have received yet is not blindly replayed between those warps. Blinkwarp keeps the packet stream ordered around the actual etherwarp uses and only releases movement and actions when their corresponding server state has caught up.

Up to **five unconfirmed warps** can overlap by default. In 1.0.3, `/zpbw chainmax [value]` queries or sets this ceiling from one to five. Each genuine confirmation frees a slot for another warp, so continued chaining does not wait for the entire chain to drain. When the configured slots are occupied, additional warp clicks can wait in order; at most one queued click is dispatched per native input tick. Lowering the ceiling preserves already-issued warps and captured waiting clicks. Queued clicks keep their original deadlines and are rechecked before dispatch.

# Prediction failures and recovery

A prediction is never treated as successful just because the client moved locally.

The genuine server teleport is always checked.

If the server sends a different destination, the prediction expires, the connection or world changes, player state becomes invalid, or another condition makes the pending prediction unsafe, Blinkwarp falls back to the authoritative server state.

Dependent queued actions which have not executed yet are cancelled rather than being fired from a position which is no longer valid.

On an actual issued-warp timeout, ZPBW abandons obsolete speculative actions and returns fresh input and physics to the native path. It retains bounded bookkeeping for uncertain issued effects; genuine late responses still use Minecraft's original handlers. Successful warps keep their existing ordering.

After uncertain recovery, new predictions pause while ordinary native gameplay continues. They resume automatically on the same connection after a genuine server position has completed its original native response handling, fresh native input has been sampled, and a normal TickEnd confirms that outstanding work has drained. A minimum of 20 completed native client ticks stabilizes this error-only transition; another native use during recovery restarts that quiet window, so continued clicking cannot reuse the first failure's expired timer. The timer alone cannot resume prediction. Queued incoming positions, new untracked uses, interrupted handlers and ownership/lifecycle changes prevent stale authority from reopening admission. If a refused warp never produces a server position, prediction waits for a later genuine server position; reconnecting is not required. Feature toggles cannot discard unfinished ownership or force this gate open.

An obsolete queued mining continuation ends quietly when the held button/target no longer supports it. It does not discard later independent clicks. Distinct pressed clicks, changed held items and invalid player/world contexts retain their normal validation and cancellation messages.

A server knockback or explosion impulse during a pending prediction also invalidates that prediction. The original native motion handler still runs. A later genuine teleport can supersede the impulse; if no teleport arrives, recovery preserves the received impulse when movement resumes.

Already-sent etherwarp uses obviously cannot be taken back, but Blinkwarp will not manufacture another successful teleport on top of a failed prediction.

**the client predicts, but the server still gets the final say.**

The focused 26.3 local test also found a specific turn-and-place compatibility restriction: the installed server check can deny an ordinary block placement when it evaluates the previous look before the following native movement update. The retained trace shows correct native click/look order and the check's timing-dependent cancellation. Preparing the camera one natural tick earlier completed the tested workload, but this was a private test variant, not a production repair or a guarantee for instantaneous turn-and-place behavior on every server. See [the validation limits](docs/validation-1.0.3.md).

# What this does not remove

Blinkwarp removes the need to wait for network latency before *locally seeing and acting from* the predicted etherwarp destination.

It does not remove network latency itself.

Anything which fundamentally requires the server to create or return something still has to wait for the server. Server-created entities, projectiles, inventory results and other authoritative state do not magically exist early just because your position was predicted.

The purpose of Blinkwarp is to make the period between clicking an etherwarp and receiving its real confirmation feel as close to zero ping as possible, while still preserving the real server packet order underneath it.

## Credits

Developed by hawkzlol with assistance from OpenAI Codex.
