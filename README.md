# Switchboard

Switchboard is an unofficial fork of [Signal for Android](https://github.com/signalapp/Signal-Android) that brings back SMS and MMS. Your Signal chats and your regular texts share one inbox, like Signal did before 2023. Signal messages stay end-to-end encrypted, and texts go through your carrier the same way they would in any other texting app.

The texting support is built as a general framework for "external" (non-Signal) message types. SMS and MMS are the first two, and others, such as RCS once GrapheneOS supports it, can be added the same way.

> **Status: early and untested on real devices.** The builds are debug builds signed with a debug key. Expect bugs, and keep a backup of your messages.
>
> Switchboard is not made, endorsed or supported by Signal Messenger, LLC. Please don't report Switchboard problems to Signal.

## What's different from Signal

**Texting**
- Send and receive SMS, and MMS with photos, video, audio and files.
- Group texts (MMS groups). If you start a group with people who aren't all on Signal, you get a group text.
- Works as your phone's default SMS app. Opening "message" from your Contacts app or from an `sms:` link opens a chat here, and quick replies from the incoming-call screen work too.
- Imports the texts already on your phone the first time you set it up. You can run it again later; it only picks up what's new.
- Can keep a copy of every text in Android's own message store, so backup tools and other SMS apps still see them. You can turn this off.
- Dual SIM: when you have more than one SIM, you choose the line for each text.
- Carrier delivery reports (off by default).

**One inbox**
- Each conversation uses Signal when the other person is on Signal. You can switch any message to SMS/MMS with a long press on the send button.
- Every text shows **SMS** or **MMS** next to its time. Text senders can be faked, so a text never looks like a Signal message.
- People who aren't on Signal show up in the new-message screen, in sharing and forwarding, and in group creation.
- Replies from a notification use the same method the chat would.

**Kept separate from Signal on purpose**
- Signal-only features stay on Signal: reactions, edits, delete for everyone, pins, polls, payments, contact cards, view-once media, disappearing messages and scheduled sends. They're hidden or blocked for texts rather than silently dropped.
- Texts never trigger Signal read receipts, even when the sender is also on Signal.
- Media you send by MMS isn't uploaded to Signal's servers first.

## Installing

Switchboard uses its own app ID (`org.thoughtcrime.securesms.sms`), so it installs **next to** official Signal instead of replacing it.

1. Open the latest successful run of the [Fork build workflow](https://github.com/lunarminus/Signal-Android/actions/workflows/fork-build.yml) and download the `signal-sms-debug-apk` artifact. You need to be signed in to GitHub.
2. Unzip it and install the APK for your phone. That's `arm64-v8a` on almost every current phone, or `universal` if you're not sure.
3. Register with your phone number as you would in Signal.

**Important:** a Signal account can only have one primary phone app. If you register Switchboard with the number you use in official Signal, your account moves to Switchboard and official Signal is signed out. Your existing Signal message history doesn't move automatically; use Signal's backup or transfer tools if you want to bring it along.

## Turning on texting

1. Go to **Settings > Chats > SMS and MMS**.
2. Turn on **Send and receive SMS/MMS** and allow the permissions it asks for.
3. Accept the prompt to make Switchboard your default SMS app. MMS and reliable SMS receiving need this.
4. Your existing texts are imported automatically. To run the import again later, tap **Import texts from your phone**.

The same screen has the other options: prefer Signal when available, carrier delivery reports, and keeping a copy in Android's message store.

## Security and privacy notes

- **SMS and MMS are not encrypted.** Your carrier, and anyone who can intercept carrier traffic, can read them. That's true of every texting app.
- **Text senders can be spoofed.** A faked text that claims to come from a Signal contact lands in that contact's chat, marked **SMS**. Treat anything sensitive that arrives as SMS with suspicion.
- **Android's message store:** with the system copy option on (the default), other apps that have SMS permission can read your texts there. Turn the option off if you don't want that.
- **MMS downloads:** when an MMS arrives, your phone fetches it from the carrier. That tells the carrier's server your phone is online. MMS from blocked senders aren't fetched at all.
- **No accidental downgrades:** with **Prefer Signal** on (the default), anyone not known to be off Signal defaults to Signal. A chat only switches to SMS by default once Signal confirms the person isn't registered.
- **Incoming MMS parsing** uses Android's open-source MMS PDU code (Java), vendored in [`external/mms/pdu`](app/src/main/java/org/thoughtcrime/securesms/external/mms/pdu/README.md).

## Known limitations

- Not yet tested on a real phone, and the builds are debug builds signed with a debug key. A release build would need its own signing key, so it couldn't update over a debug install.
- Only the English text has been reworded. Other languages still call the app "Signal" in places where they mean the app.
- Disappearing-message timers don't apply to texts.
- Push notifications may fall back to a slower background connection under the new app ID, which can use more battery.
- RCS isn't supported yet. It will be added as another transport once GrapheneOS exposes RCS to apps.

## How it's built

Everything Switchboard adds lives in [`app/src/main/java/org/thoughtcrime/securesms/external/`](app/src/main/java/org/thoughtcrime/securesms/external/), plus small, commented hooks in upstream files (look for `Fork:`). Keeping it that way makes it easier to pull in new Signal releases.

| Piece | What it does |
| --- | --- |
| `ExternalTransport`, `ExternalTransports` | The framework. Each transport declares what it can do (`ExternalTransportCapabilities`), who it can reach and how it sends. The registry picks one for each message. |
| `ExternalMessageSender`, `ExternalIncomingMessages` | Shared send and receive paths. Outgoing messages have Signal-only fields removed and are tagged with their transport (`MessageExtras.externalTransport`). Incoming ones are stored like any other message and notified. |
| `ExternalSendTypes` | Decides which send options a chat offers (Signal, SMS/MMS, one per SIM) and which one it starts with. |
| `sms/` | SMS send/receive jobs, carrier status receivers, and the "respond via message" service. |
| `mms/` | MMS composing and sending through `SmsManager`, WAP push handling, downloads, carrier limits, and the vendored PDU library. |
| `system/` | Mirroring to Android's message store, and the import of existing texts. |
| `ui/` | The settings screen, the `sms:` link handler, and the SMS/MMS labels. |

### Adding a new transport (for example, RCS)

1. Add a value to `ExternalTransportType`.
2. Implement `ExternalTransport` with its capabilities, availability check, reachability rules and a send job.
3. Register it in `ExternalTransports` and its jobs in `JobManagerFactories`.
4. Hand incoming messages to `ExternalIncomingMessages.insert` with the new type.

The send-type picker, labels, read-receipt filtering and the rest pick it up from there.

## Building

Pushes to `main` and `feature/**` branches are built by [`.github/workflows/fork-build.yml`](.github/workflows/fork-build.yml) on standard GitHub runners, which upload the debug APKs as an artifact.

To build locally you need JDK 21 and the Android SDK:

```sh
./gradlew :Signal-Android:assemblePlayProdDebug
```

The APKs end up in `app/build/outputs/apk/`.

## Staying current with Signal

Switchboard follows upstream Signal releases. To update, merge or rebase the latest upstream tag onto this branch and resolve conflicts in the files marked `Fork:`.

## Credits and license

Switchboard is built on [Signal for Android](https://github.com/signalapp/Signal-Android), copyright 2013 Signal Messenger, LLC, and is licensed under the [GNU AGPLv3](https://www.gnu.org/licenses/agpl-3.0.html), like Signal itself.

The MMS PDU library in `external/mms/pdu` comes from the Android Open Source Project and is licensed under the Apache License 2.0. See its [README](app/src/main/java/org/thoughtcrime/securesms/external/mms/pdu/README.md) for details.

"Signal" is a trademark of Signal Messenger, LLC. Switchboard is not affiliated with or endorsed by Signal Messenger, LLC.

### Cryptography notice

This distribution includes cryptographic software. The country in which you currently reside may have restrictions on the import, possession, use, and/or re-export to another country, of encryption software.
BEFORE using any encryption software, please check your country's laws, regulations and policies concerning the import, possession, or use, and re-export of encryption software, to see if this is permitted.
See <http://www.wassenaar.org/> for more information.

The U.S. Government Department of Commerce, Bureau of Industry and Security (BIS), has classified this software as Export Commodity Control Number (ECCN) 5D002.C.1, which includes information security software using or performing cryptographic functions with asymmetric algorithms.
The form and manner of this distribution makes it eligible for export under the License Exception ENC Technology Software Unrestricted (TSU) exception (see the BIS Export Administration Regulations, Section 740.13) for both object code and source code.
