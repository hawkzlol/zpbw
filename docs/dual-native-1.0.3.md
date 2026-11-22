# Native builds for 26.2 and 26.3

One product version ships as two exact-native artifacts. See [validation and limitations](validation-1.0.3.md) for the scoped checks and remaining issues.

## One source, two independent builds

`src/main` and `src/test` are canonical. `native-targets.json` allowlists narrow same-path replacements in `src/native26_2` and `src/native26_3`; native test fixtures are separate from shared behavioral tests. `versions/mc26_2` and `versions/mc26_3` select their own exact game, loader and API through `gradle/zpbw-native.gradle`. Neither consumes the other lane's outputs. No bench mirror or private harness source belongs in the install JAR.

Run `python tools/verify_dual_native.py` with Java25. The wrapper remains Gradle9.6.0 with its checksum. Kotlin2.4.10, Loom1.17.11 and Fabric Language Kotlin1.13.13+kotlin.2.4.10 are shared. The 26.2 lane retains Loader0.19.3/API0.155.2+26.2; 26.3 uses Loader0.19.5/API0.165.0+26.3. Dependency locks and runtime/source hash inventories are per lane. Use `--initialize-locks` only for an intentional lock bootstrap; its changed inputs cannot collect a pair, so rerun unchanged.

The verifier refuses missing/failed/skipped tests, wrong routes, opposite-lane dependencies, duplicate entries/FQCNs/resources, private code, wrong metadata/identity, changed inputs and incomplete/stale receipts. It collects only the successful same-run pair, never the newest JAR from an unrelated directory. CI runs the same complete two-lane command on Windows and Linux. Keep prior pair directories immutable.

Downloaded dependencies retain strict checksum verification. Loom's locally generated Minecraft archive can have different ZIP metadata on different machines. Only that exact per-native generated artifact is exempted from the container checksum: a required pre-compilation task instead verifies every decompressed entry against the audited content digest, including classes, resources and version identity. Missing, changed, added or duplicate entries fail the build. Changes to those expected digests require an independent native-input review.

## Commands and migration

`/zpbw chainmax` prints `Current chain maximum: <value>.` without writing. `/zpbw chainmax <integer>` normalizes to1–5 and prints `Set chain maximum to <value>.` only after same-directory atomic persistence succeeds. Decimal command syntax is invalid. Integral JSON2.0 is accepted; invalid JSON field values default5 without discarding other valid preferences. A load-time migration persistence failure retains valid parsed preferences and reports pending migration; a command save failure preserves the previous live and disk settings.

The ceiling controls overlap, not server speed. Lowering it retains all pending generations, timers, held actions and original waiting-input deadlines. New admission requires pending below the ceiling plus every existing source-cut, recovery, click and ordinary-tick guard. Raising it never bypasses those guards. `/off` cancels unissued waiting inputs while preserving issued-response ownership until normal settlement or failure recovery.

## Native boundaries

26.2 receives a genuine teleport and vanilla sends ID acceptance followed by PosRot. 26.3 sends one genuine combined ID/XYZ/yaw/pitch acceptance, then runs native block-prediction/destruction cleanup. The typed response ledger observes those actual sends and handler completion; Netty admission is not server acceptance. Combined acknowledgement history distinguishes absent wire collision flags from the native server consumer's derived false/false bookkeeping. A separate Rotate packet keeps its own native response route.

26.3 samples physics before `LocalPlayer.sendChanges`; queued aim spans that genuine dispatch phase with finally cleanup. NSD's early input consumes the first required natural commitment: later movement changes may still wait for its end. No second input, extra TickEnd, artificial simulation or obsolete deferred replay is added. Repeated singleton Punch objects are traced by send occurrences and click scope, never used as causal occurrence IDs.

## Runtime gates and rollback

26.3's combined teleport acknowledgement does not replace the ordinary movement-position reminder. Chaining preserves the reminder derived from transmitted natural ticks, and pauses new warp admission before that original report can be repeatedly coalesced away. The bounded waiting queue keeps original deadlines and revalidation. The pause ends on an uncancelled position send-delegate return, not an empty chain or combined acknowledgement; this is local request ordering, not remote acceptance. Positionless movement requests, including separate rotation responses, are counted independently of TickEnd. Arbitrary server-driven rotation bursts and transport failures still require separate recovery/runtime coverage. No packet, simulation tick or TickEnd is constructed by this gate.

A collected build is labelled BUILT_UNQUALIFIED by the build tool. Runtime assessments separately identify the exact JAR hashes, actual native effects, original controls and restored state. No quiet log substitutes for an action effect. Changes need relevant native runtime checks; unchanged-component evidence reuse needs an explicit source and behavior argument. Dedicated no-ZPBW controls include instrumentation and are not pristine vanilla.

Build verification never connects remotely, installs into an ordinary client or publishes a release. Keep the previous install JAR and exact config backup outside mods. To roll back with the client closed, archive the candidate, restore the verified prior matching-native JAR without duplicate standalone mods, restore the chosen pre-migration config backup only if desired, and verify SHA256. Where no prior matching-native release exists, disable or remove ZPBW while retaining its config. Never overwrite later user preferences silently.
