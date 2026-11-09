# Axon mod — what this fork changes

Unofficial fork of [Capri-cmd/Capri-Mobile](https://github.com/Capri-cmd/Capri-Mobile)
v0.4.2-beta, built for personal, noncommercial use under the upstream license
(PolyForm Noncommercial 1.0.0 — see `LICENSE` and `NOTICE.md`, unchanged).

## The change

Upstream, her brain can be a model on the phone, LM Studio on your PC, or
NVIDIA's cloud. The LM Studio client never sends an API key, so no other
OpenAI-style cloud endpoint can be used. This fork widens that one slot:

- **Brain tab → LM Studio card** gains an *API key* field (stored with the
  app's secrets, excluded from backups, same as the NVIDIA key). Type a cloud
  endpoint's full address (`https://…`) as the address and paste its key;
  Connect lists that endpoint's models and she thinks with the one you pick.
  With the key empty, the card behaves exactly as upstream (your own PC).
- Cloud requests carry `Authorization: Bearer <key>` and a browser
  User-Agent (some gateways refuse the default Java one). The model list
  request (`/v1/models`) is authenticated the same way.
- **Safety default:** while a keyed endpoint is her brain, she treats it as
  strict cloud — *React on her own* sends nothing to it by itself unless you
  turn on "Let her look on her own" in the LM Studio card. Your own chats
  with her still go there, as with any cloud brain.
- Version bumped to `0.4.2-axon.1` (versionCode 9) so the build is
  recognisable in the app.

Example: address `https://api.axonagent.xyz/v1`, key = your AxonAgent key.

## Building

APKs are built by GitHub Actions (`.github/workflows/build.yml`) and attached
to the `capri-axon-v0.4.2-1` release. CI signs with a debug key, so Android
treats this as a different signer than the official build: uninstall the
official Capri first; official updates will not install over this one either.

## Files touched

`Prefs.kt` (the key), `LmStudio.kt` (auth + UA headers), `Brain.kt` (passes
the key, strict-cloud flag), `CapriService.kt` (feed gate), `BrainScreen.kt`
(the key field and its opt-in toggle), `app/build.gradle.kts` (version).
Nothing else differs from upstream v0.4.2-beta.
