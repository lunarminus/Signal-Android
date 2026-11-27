# MMS PDU library

These classes encode and decode WAP/MMS PDUs (OMA MMS encapsulation). They are copied from AOSP's
`frameworks/opt/mms` (`com.google.android.mms` and `com.google.android.mms.pdu`, via the LineageOS
mirror, branch `cm-11.0`), licensed under the Apache License 2.0 (see the header in each file).

Changes made for this fork:

- Repackaged to `org.thoughtcrime.securesms.external.mms.pdu`, so they can't clash with the hidden
  copies inside the Android framework.
- `PduPersister` (system MMS provider storage) was left out; this app stores MMS in its own database.
- `PduParser` no longer reads the hidden framework resource `config_mms_content_disposition_support`;
  callers set it with `PduParser.setParseContentDisposition()` from carrier config.
