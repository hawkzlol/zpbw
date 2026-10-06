# External prediction ownership

Other prediction features can coordinate with ZPBW using `com.hawkslol.zpbw.api.ExternalPredictionLease`. The earlier `dev.zpbw.candidate.api.ExternalPredictionLease` address remains a forwarding bridge for existing integrations.

The Java-static API is:

```java
int protocolVersion(); // 1
boolean tryAcquire(Object owner);
boolean owns(Object owner);
boolean release(Object owner);
```

Call ownership methods on the Minecraft client thread. Pass a stable, non-null owner token; tokens compare by identity. `protocolVersion()` can be read from any thread.

Acquire before making speculative player changes or sending dependent actions. Acquisition succeeds only with an active, loaded game session and a fully drained ZPBW prediction/action/input pipeline. A denied acquisition has no toggle or reservation side effect. Another owner cannot steal or release the lease.

While leased, ZPBW does not predict, retain or replay the external feature's gameplay packets and does not apply NoSneakDelay input changes. Real vanilla teleport handling continues. The caller must retain the lease through its own pending confirmations and recovery, then release it after its own state has drained.

World, player or connection replacement invalidates ownership. Check `owns(owner)` at lifecycle boundaries. Acquiring a lease is not proof that a caller's own packet or physics behavior is correct.
