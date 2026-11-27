# RCS transport (experimental)

This document describes the RCS transport added to the fork's external-transport framework: what it
does, what works today, the one layer that is still a frontier, and the device prerequisites and
caveats. It is written for whoever finishes or reviews the implementation.

## TL;DR

A third-party Android app **cannot** use carrier RCS through any public API. Every major US carrier
routes RCS through Google **Jibe**, and Jibe refuses to provision a client unless the request carries
a Google **DroidGuard** attestation token — an internal Play-services signal that normal apps can't
produce. GrapheneOS's own RCS effort (GrapheneOS/Messaging issue #456, Nov 2026) confirmed this and
is, as of this writing, still a proof-of-concept blocked on "an unclear reg error response from
Jibe."

So this transport is built in the only shape that can work for a sideloaded app: it reproduces the
DroidGuard attestation (from the CC0 PoC in that issue), runs the GSMA HTTP provisioning flow against
Jibe, and plugs into the external framework exactly like SMS/MMS — preferred when a contact is RCS
-reachable, falling back to MMS/SMS otherwise. The networking layer underneath (SIP/MSRP/MLS) is
defined as an interface with a stub, because it cannot be finished or verified without a device that
can actually register against Jibe. **Until that layer is implemented the transport reports itself
unavailable, so turning RCS on never breaks SMS/MMS.**

## Layers

| Layer | File(s) | Status |
|---|---|---|
| DroidGuard attestation | `rcs/attestation/DroidGuardClient.kt` | Implemented (from CC0 PoC). Binds GMS `IGmsServiceBroker`, drives DroidGuard, returns a token. Runtime-verifiable only on a device with sandboxed Play + signature spoofing. |
| HTTP provisioning (GSMA RCC.14 autoconfig) | `rcs/provisioning/RcsProvisioningClient.kt` | Implemented to spec: builds the autoconfig URL, attaches the token, handles the HTTP 511 OTP round-trip, parses the config. Exact Jibe host/header set needs confirming against live traffic. |
| Config XML parsing | `rcs/provisioning/RcsConfig.kt`, `RcsConfigParser.kt` | Implemented. Parses the OMA-DM `characteristic`/`parm` tree into the IMS/MSRP/capability fields the stack needs. |
| Transport + framework wiring | `rcs/RcsTransport.kt`, edits to `ExternalTransportType`, `ExternalTransports`, `ExternalMessageSender` | Implemented. RCS registered first; send routed by the stored transport tag; feature stripping driven by capabilities. |
| Capability cache + refresh | `rcs/RcsCapabilityCache.kt`, `rcs/jobs/RcsCapabilityRefreshJob.kt` | Implemented. Async per-contact reachability with expiry so `canReach` stays synchronous. |
| Jobs | `rcs/jobs/RcsSendJob.kt`, `RcsProvisioningJob.kt` | Implemented. Send with MMS/SMS fallback; provisioning with OTP handling. |
| Coordinator + inbound routing | `rcs/RcsManager.kt` | Implemented. Routes inbound messages/receipts into `ExternalIncomingMessages`. |
| **SIP / MSRP / MLS networking** | `rcs/sip/RcsSipClient.kt` (interface), `DefaultRcsSipClient.kt` (stub) | **Not implemented — the frontier.** See below. |

## The frontier: `DefaultRcsSipClient`

This is the one unfinished layer. It cannot be written and verified in isolation because a SIP
REGISTER only succeeds once the DroidGuard-backed provisioning above has produced valid IMS
credentials on a real device, and Jibe's exact behaviour there is still unconfirmed (the same wall
GrapheneOS hit). To complete it, implement, in order:

1. **TLS SIP** — REGISTER against the P-CSCF from `RcsConfig.sip` with IMS-AKA or SIP Digest auth,
   keep-alive, and re-REGISTER before expiry. No SIP library ships with the app; either vendor a
   minimal stack or bind a privileged/native service if GrapheneOS exposes one.
2. **Capability exchange** — SIP OPTIONS (RCC.07 §2.6) to answer `queryCapabilities`.
3. **MSRP** — SDP offer/answer in SIP to carry message bodies and file transfers.
4. **MLS E2EE** — GSMA Universal Profile 3.0 (RFC 9420), interoperable with Google Messages and iOS.
5. **IMDN** — delivery/display receipts mapped back through `RcsEventListener`.

The interface boundary means this can also be swapped for a native or privileged implementation
without touching the transport.

## Device prerequisites

RCS provisioning needs a DroidGuard token, which requires **one** of:

- **GrapheneOS** with sandboxed Google Play enabled **and** a signature-spoofing allowance so this app
  can present the Google Messages identity to GMS (GrapheneOS's own plan spoofs one of its system
  apps). Removing Play after activation stops RCS working, because the client must check in.
- **microG** with its DroidGuard compatibility layer, allowlisted.

On stock Android without either, `DroidGuardClient.requestToken` fails and provisioning returns
`Failed`; the transport stays unavailable and the app uses MMS/SMS.

It also needs the default-SMS-app role (for fallback and the system store) and `READ_PHONE_STATE`
(and `READ_PHONE_NUMBERS` on newer Android) to read IMSI/MSISDN for provisioning. Note that `IMSI`
and `line1Number` are restricted on Android 10+ and may be null for a non-privileged app, which is a
known provisioning obstacle.

## Caveats

- **Verification.** None of the network-touching code (attestation, provisioning, SIP) has been run
  against a live device or Jibe; it is written to the public specs and the CC0 PoC. The SafeParcel
  framing in `DroidGuardClient` and the exact Jibe autoconfig host/headers are the most likely things
  to need adjustment once tested on-device.
- **Dependence on Google.** This path relies on Google's infrastructure and attestation. It does not
  make RCS "Google-free." The genuinely Google-free route is GrapheneOS shipping RCS as an OS service;
  if they expose a system API for it, `RcsSipClient` should be reimplemented against that instead.
- **Legality / ToS.** Presenting another app's package identity to GMS to obtain a DroidGuard token
  is a reverse-engineering technique and may violate Google's terms. Ship it as an opt-in,
  clearly-experimental feature.
- **It is off by default** (`RcsPreferences.isEnabled = false`).
