# Zero Ping Blinkwarp!

Immediately teleports you to your targeted block when you attempt an etherwarp.

Zero Ping Blinkwarp predicts the result of your etherwarp locally and moves you there immediately, without waiting for the server's teleport response to make the full round trip first.

Unlike older zero ping implementations which effectively left you limited to camera movement while waiting for the real teleport to arrive, Blinkwarp allows you to continue moving, *interact*, click entities, use items, change slots, swing, and generally continue playing normally. Anything that cannot safely be sent yet is held temporarily and replayed in the correct order once the real server teleport arrives.

The goal is not to fake the server teleport or replace Minecraft's normal teleport handling. The real etherwarp use is still sent to the server, the server still performs its own raycast, and the genuine teleport response is still processed normally. Blinkwarp simply predicts the result ahead of time and keeps your client responsive while waiting for that confirmation.

The mod has been tested in a local server lab across different latencies, chained warps, interactions and recovery cases. That does not cover every server or combination of mods. It will remain actively maintained until this game no longer interests me, or I consider the mod to be in a strong enough state to leave as-is.

Use `/zpbw` to see the available commands.

## Installation

Download **[ZPBW 1.0.1](https://github.com/hawkzlol/zpbw/releases/tag/v1.0.1)** from GitHub Releases.

ZPBW is a client-side mod for **Minecraft Java 26.2**, using **Java 25**, **Fabric Loader 0.19.3 or newer**, **Fabric API** and **Fabric Language Kotlin**. Put the ZPBW JAR and its required Fabric dependencies in your instance's `mods` folder. Remove older ZPBW JARs before installing a replacement.

## Commands

Every setting below is saved between sessions.

| Command | What it does |
| --- | --- |
| `/zpbw` | Shows the command list. |
| `/zpbw on` / `/zpbw off` | Enables or disables prediction. |
| `/zpbw nosneakdelay` or `/zpbw nsd` | Toggles NoSneakDelay. Add `on` or `off` to set it explicitly. |
| `/zpbw timeout <ticks>` | Sets the timeout for new warps. Values are clamped to 2–20; the default is 20. |
| `/zpbw logs` | Copies troubleshooting information from the current client session to your clipboard. |

NoSneakDelay attempts to send sneak input earlier while preserving packet order. Timeout ticks count nonzero server Ping packets; their cadence can vary by server, so the value is not a fixed wall-clock duration.

## Update notifications

ZPBW checks this repository's latest stable GitHub release once per client session, in the background. If a newer version exists, a notification appears a few seconds after you join SkyBlock, like the first-install welcome. It does not download or install updates.

No release, an inaccessible repository or a failed request simply means no update notification. Pre-releases are not offered by this checker.

SkyBlock detection reads Minecraft's sidebar and tab list directly. It does not require another SkyBlock mod or inherit another mod's developer overrides.

## Reporting a problem

Run `/zpbw logs` after the problem occurs, **before closing Minecraft**, and include the copied text in a [bug report](https://github.com/hawkzlol/zpbw/issues/new?template=bug_report.yml). Explain what you clicked, what you expected and what happened instead. A short clip can help with movement or interaction problems.

Diagnostics are kept in a bounded in-memory buffer for the current launch; ZPBW does not continuously write diagnostic files. The report includes relevant mod versions, settings and recent prediction events, including coordinates. It excludes chat, account details, server addresses and raw packet payloads. Review the copied text before posting it publicly.

## Building from source

With Java 25 installed:

```sh
./gradlew test build
```

On Windows, use `gradlew.bat test build`. The standalone mod is written to `build/libs/zpbw-1.0.1.jar`.

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

Up to **five unconfirmed warps** can overlap before the first confirmation arrives. This limit is fixed. After the first confirmation of an overlapping chain, new warp dispatch briefly waits for the remaining warps to settle. This bounded pause keeps continued chaining in order rather than letting prediction run indefinitely ahead of the server.

# Prediction failures and recovery

A prediction is never treated as successful just because the client moved locally.

The genuine server teleport is always checked.

If the server sends a different destination, the prediction expires, the connection or world changes, player state becomes invalid, or another condition makes the pending prediction unsafe, Blinkwarp falls back to the authoritative server state.

Dependent queued actions which have not executed yet are cancelled rather than being fired from a position which is no longer valid.

Already-sent etherwarp uses obviously cannot be taken back, but Blinkwarp will not manufacture another successful teleport on top of a failed prediction.

**the client predicts, but the server still gets the final say.**

# What this does not remove

Blinkwarp removes the need to wait for network latency before *locally seeing and acting from* the predicted etherwarp destination.

It does not remove network latency itself.

Anything which fundamentally requires the server to create or return something still has to wait for the server. Server-created entities, projectiles, inventory results and other authoritative state do not magically exist early just because your position was predicted.

The purpose of Blinkwarp is to make the period between clicking an etherwarp and receiving its real confirmation feel as close to zero ping as possible, while still preserving the real server packet order underneath it.
