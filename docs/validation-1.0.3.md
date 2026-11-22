# 1.0.3 validation and known limitations

Both exact native artifacts were independently built and tested with Java 25. The shared suite passed **392 test cases per native version** (784 executions; 392 shared IDs).

The final prediction follow-up exercised **24 moving/jumping/sneak windows across both versions**, all with exact native server destinations and completed acceptances. Coverage included Jump Boost V, rising/apex/falling, strafe, early/late/brief sneak, input-supplied sneak, camera changes, NSD on/off and feature-OFF controls at requested RTTs of 25–300 ms. Two deliberate wrong-position cases verified genuine response handling, diagnostic output and automatic recovery on both versions. No Trips appeared in those scoped checks. An additional two-window diagnostic exercised fallback and a subsequent successful warp; its intended unrelated-position branch was not exercised.

Earlier focused testing covered native interactions, chaining, timeout/refusal/late-response recovery and lifecycle changes. Those observations retain their actual artifact identities; reuse is limited to unchanged components. Historical broader campaigns are not represented as completed by this release. Build receipts describe builds separately from functional observations.

## Limitations

- A recorded rising one-block grazing-edge error is reproduced and repaired. A separate intermittent falling 1.414-block remote discrepancy remains unexplained. Changing every ray's eye height would regress known matches, so no speculative height change was applied. New bounded diagnostics record the ray, sampled cell changes, body height and input commitment for subsequent reports.
- The private local model does not prove Hypixel's exact source/eye selection. Input-supplied sneak tests represent a compatible input boundary, not every third-party mod's full runtime.
- An instantaneous camera turn followed by block use can encounter a local RotationPlace timing restriction before the next native look update. Prepared-camera runs are separate evidence; the original denied interaction remains recorded. Historical DuplicateRotPlace observations and a separate 26.2 RotationPlace recovery warning remain disclosed.
- NSD preserves its first already-transmitted input commitment. Movement changes during that interval can still wait until the following sample.
- Raw server Ping cadence, very high latency and remote packet-rate limits can still affect timeouts or behavior. The exact reported remote packet-rate kick was not reproduced conclusively.
- Native Linux/macOS clipboard failure handling was not runtime-tested. The console fallback and export handling have offline regression coverage.

Use `/zpbw logs` immediately after a problem, before closing Minecraft. Reports include coordinates and mod versions: review them before sharing publicly. There is no guarantee of compatibility with every server or combination of mods.
