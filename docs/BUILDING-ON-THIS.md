# Building on this

Everything the speaker needs is plain HTTP and SOAP on your local network, so none of this depends on Android. Here's where to start if you want a version for another platform.

Read the [protocol notes](PROTOCOL.md) first. **We haven't built any of the ideas below**, so treat them as starting points.

## What any client needs

| Job | How | Android reference |
|---|---|---|
| Find speakers | mDNS `_linkplay._tcp` and SSDP | `net/Discovery.kt` |
| Send commands | HTTPS on 4443 with the client certificate | `net/SpeakerClient.kt`, `net/LinkPlayTls.kt` |
| Read state | UPnP `GetInfoEx`, `GetVolume`, `GetMute` | `net/Upnp.kt` |
| Play a stream | `SetAVTransportURI` and `Play`, or `setPlayerCmd:play` for HLS | `playStream` in `SpeakerViewModel.kt` |
| Keep buttons working | Rebuild queues that are missing or empty | `ensurePresetQueues` in `net/Upnp.kt` |

Two traps to avoid:

- Don't depend on `getPlayerStatus`. It doesn't exist on this firmware.
- Decide "the speaker is offline" from network failures only. An error reply still means the speaker is alive.

`net/Stations.kt` has the station list, and `net/NtsApi.kt` shows how to get live NTS pictures.

## iOS app

The protocol carries over directly. The platform work is mostly permissions and certificates:

- **Local network:** add `NSLocalNetworkUsageDescription`, and list `_linkplay._tcp` under `NSBonjourServices`.
- **Discovery:** use `NWBrowser` for mDNS. Sending SSDP multicast needs Apple's `com.apple.developer.networking.multicast` entitlement, which you have to request. mDNS alone may be enough, with manual IP entry as the fallback.
- **Mutual TLS:** convert the PEM to a `.p12`, then answer `URLSession` authentication challenges with the client identity and accept the speaker's self‑signed certificate.
- **Plain HTTP for UPnP:** allow it with `NSAllowsLocalNetworking` in App Transport Security.

The Android app's approach to polling and reconnecting should translate well to a SwiftUI view model.

## Raspberry Pi or NAS service

This is the most valuable idea, because it could solve the app's biggest limit. After a power cut the buttons need a client to rebuild their queues, and a small always‑on service could do that with no phone.

The idea is a loop that runs every minute or so:

```text
for each speaker:
    presets = GetKeyMapping
    for each radio preset:
        queue = BrowseQueue(exact preset name)
        if queue is missing, has the wrong PressType, or has no <URL>:
            DeleteQueue(name)
            AppendQueue(queue built from the preset)
```

Only UPnP on port 49152 is needed for this, so it doesn't require the certificate. It would work well as a Docker container, or a small script with a systemd timer. Keep it on the same network as the speaker, and with Docker use host networking if you add discovery.

Other ideas for an always‑on service:

- A small web page, so anyone at home can use the speaker from a browser
- Home Assistant support. [hass-audio-pro](https://github.com/LundstromJoseph/hass-audio-pro) already exists, so look there first
- Playing MP3s from a NAS by serving them over HTTP and sending the speaker the URL. It should work like any other stream, but we haven't tested it

## Saving presets

`SetKeyMapping` works, and the Android app uses it (`savePreset` in `net/Upnp.kt`). Remember the order: write the key, delete the button's old queue, then add a matching one. Leaving the old queue behind means two queues claim the same button. The format that worked is in the [protocol notes](PROTOCOL.md).

## Sharing what you find

If you test another model or firmware, or answer one of the [open questions](PROTOCOL.md#open-questions), please open an issue or pull request so the notes can grow.
