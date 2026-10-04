# Zero Ping Blinkwarp!

Immediately teleports you to your targeted block when you attempt an etherwarp.

Zero Ping Blinkwarp predicts the result of your etherwarp locally and moves you there immediately, without waiting for the server's teleport response to make the full round trip first.

Unlike older zero ping implementations which effectively left you limited to camera movement while waiting for the real teleport to arrive, Blinkwarp allows you to continue moving, *interact*, click entities, use items, change slots, swing, and generally continue playing normally. Anything that cannot safely be sent yet is held temporarily and replayed in the correct order once the real server teleport arrives.

The goal is not to fake the server teleport or replace Minecraft's normal teleport handling. The real etherwarp use is still sent to the server, the server still performs its own raycast, and the genuine teleport response is still processed normally. Blinkwarp simply predicts the result ahead of time and keeps your client responsive while waiting for that confirmation.

This mod has been put through very rigorous testing to make sure as many edge cases as possible are accounted for. It will also remain actively maintained until this game no longer interests me, or I consider the mod to be in a strong enough state to leave as-is.

Use `/zpbw` to see the available commands.

Use `/zpbw help` to see every command and its full description.

# How it works, and why.

Normally, when you etherwarp, your client sends a `ServerboundUseItemPacket` to the server.

The server receives that use, performs the etherwarp raycast, determines where you should land, and sends a `ClientboundPlayerPositionPacket` back to your client containing the resulting teleport.

Only after that packet reaches you does your client actually get placed at the destination.

That means the visible result of an etherwarp normally has to wait for a full client -> server -> client round trip. The higher your latency is, the more noticeable that delay becomes.

Zero Ping Blinkwarp removes that delay from your side of the interaction.

When you attempt an etherwarp, Blinkwarp performs the same destination prediction locally. If a valid destination is found, your client is moved there immediately while the real `ServerboundUseItemPacket` is still sent to the server normally.

The important difference is what happens after that.

Instead of freezing you in place or only allowing camera movement until the server catches up, Blinkwarp lets your local game continue normally. Movement, clicks, interactions, swings, slot changes and other relevant actions are kept in an ordered queue when they cannot safely be sent yet.

The flow is roughly:

**Use Item -> Predict Destination -> Teleport Locally -> Send Real Use -> Hold Dependent Actions -> Receive Real Teleport -> Verify Prediction -> Replay Held Actions**

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

There is a configurable limit to how many unconfirmed warps may exist at once so that prediction cannot run indefinitely ahead of the server.

# Prediction failures and recovery

A prediction is never treated as successful just because the client moved locally.

The genuine server teleport is always checked.

If the server sends a different destination, the prediction expires, the connection or world changes, player state becomes invalid, or another condition makes the pending prediction unsafe, Blinkwarp falls back to the authoritative server state.

Dependent queued actions which have not executed yet are cancelled rather than being fired from a position which is no longer valid.

Already-sent etherwarp uses obviously cannot be taken back, but Blinkwarp will not manufacture another successful teleport on top of a failed prediction.

In short:

**the client predicts, but the server still gets the final say.**

# What this does not remove

Blinkwarp removes the need to wait for network latency before *locally seeing and acting from* the predicted etherwarp destination.

It does not remove network latency itself.

Anything which fundamentally requires the server to create or return something still has to wait for the server. Server-created entities, projectiles, inventory results and other authoritative state do not magically exist early just because your position was predicted.

The purpose of Blinkwarp is to make the period between clicking an etherwarp and receiving its real confirmation feel as close to zero ping as possible, while still preserving the real server packet order underneath it.
