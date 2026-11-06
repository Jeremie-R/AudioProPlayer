# Speaker protocol notes

What we learned about an Audio Pro speaker by testing it directly. Most LinkPlay documentation describes older firmware, and several documented commands don't work here.

**Tested on:** Audio Pro ADDON C10 MkII, firmware 1.56 (`audiopro_c10-user 1.56`, released December 2024). Treat everything here as true for that speaker and worth checking on any other.

In the examples, replace `SPEAKER_IP` with your speaker's address. Run them from this repository's root so the certificate path works.

## Connections

| Port | What it is | Notes |
|---|---|---|
| 4443 | HTTPS API | Requires mutual TLS with the LinkPlay client certificate. This is the one to use |
| 8443 | HTTPS | Answered 404 |
| 443, 80 | Closed | |
| 49152 | UPnP | Plain HTTP. No certificate needed |

Mutual TLS uses `app/src/main/res/raw/linkplay_client.pem`, which holds the key and the certificate. The speaker's own certificate is self‑signed, so server verification has to be off.

```bash
curl -k --cert app/src/main/res/raw/linkplay_client.pem "https://SPEAKER_IP:4443/httpapi.asp?command=getStatusEx"
curl http://SPEAKER_IP:49152/description.xml
```

Discovery uses mDNS (`_linkplay._tcp`) and SSDP (`urn:schemas-upnp-org:device:MediaRenderer:1`).

The UPnP description leaves `manufacturer` and `modelName` empty, and users rename their speakers, so don't identify a speaker by name. The `urn:schemas-wiimu-com:service:PlayQueue:1` service is a better sign.

## HTTP commands

`https://SPEAKER_IP:4443/httpapi.asp?command=...`

| Command | Result |
|---|---|
| `getStatusEx` | Works. Returns device info as JSON |
| `setPlayerCmd:vol:N` | Works, confirmed by reading the volume back |
| `setPlayerCmd:mute:0` / `1` | Works, confirmed the same way |
| `setPlayerCmd:pause`, `resume` | Works, confirmed by ear |
| `setPlayerCmd:switchmode:bluetooth` | Works |
| `setPlayerCmd:switchmode:wifi` | Works, but stops the stream and leaves `NO_MEDIA_PRESENT` |
| `setPlayerCmd:play:<url>` | Works, and is the only URL route that accepts HLS. Encode `%` as `%25`. The speaker then reports no title or URL |
| `setPlayerCmd:next`, `prev` | Replies OK. Effect not checked |
| `getPlayerStatus`, `getPlayerStatusEx` | **`unknown command`** |
| `getStatus`, `getMetaInfo` | **`unknown command`** |
| `getPresetInfo`, `getPresetList` | **`unknown command`** |
| `MCUKeyShortClick:N` | **Replies OK but pauses playback.** It does not start preset N |
| `getsyslog` | Offers a log file, but it isn't readable (it looks encrypted) |

`getPlayerStatus` failing is the important one: most libraries treat it as the main status call. Treating that reply as a lost connection causes an endless reconnect loop. All player state has to come from UPnP.

## UPnP

Control URLs, all under `http://SPEAKER_IP:49152`:

| Service | Control URL |
|---|---|
| AVTransport | `/upnp/control/rendertransport1` |
| RenderingControl | `/upnp/control/rendercontrol1` |
| PlayQueue (`urn:schemas-wiimu-com:service:PlayQueue:1`) | `/upnp/control/PlayQueue1` |

### Reading state

- `RenderingControl#GetVolume`, `#GetMute`.
- `AVTransport#GetInfoEx` is a LinkPlay addition that returns nearly everything in one call: `CurrentTransportState`, `CurrentVolume`, `PlayMedium`, `TrackSource`, `TrackURI`, `RelTime`, and `TrackMetaData` (escaped DIDL‑Lite with `dc:title`).

`PlayMedium` tells you how something started:

| `PlayMedium` | Meaning |
|---|---|
| `RADIO-NETWORK` | A queue or preset. Reports the real name and URL |
| `THIRD-DLNA` | Started with `SetAVTransportURI` |
| `BLUETOOTH` | Bluetooth input |
| `UNKNOWN` | Nothing, or a stream started with `setPlayerCmd:play`, which reports no name or URL |

### Playing a stream

`AVTransport#SetAVTransportURI` with DIDL metadata, then `#Play`. The title you send comes back in `GetInfoEx`, and URLs with `?` and `&` work once XML‑escaped.

This route rejects HLS (`.m3u8`) with **UPnP error 714**, whatever MIME type you declare. Use `setPlayerCmd:play`, or a queue, for those.

## Presets and the physical buttons

This is the main discovery.

### Reading and writing

`PlayQueue#GetKeyMapping` returns a `KeyList` XML document. It lists `Key0` to `Key21`, and keys 1 to 6 are the physical buttons on this speaker:

```xml
<Key1>
  <Name>NTS1_#~1793181170</Name>
  <Url>https://stream-relay-geo.ntslive.net/stream?client=direct</Url>
  <Metadata>...escaped DIDL-Lite...</Metadata>
  <Source>CustomRadio</Source>
</Key1>
```

`Name` can carry a `_#~timestamp` suffix. `Source` was `CustomRadio` or `Linkplay Radio`. Some entries add `<PicUrl>`.

`PlayQueue#SetKeyMapping` writes the whole list back. We changed three keys this way and read them back correctly, and their buttons then worked. Send the complete list, because a mistake affects every preset. Back it up first.

### Why buttons flash and do nothing

**A button only plays if the speaker holds a queue that:**

1. has `ListName` **exactly** equal to the key's `Name`, suffix included
2. has `PressType` set to the button number
3. contains its track

How we established that, by pressing real buttons:

| Queue state | Result on the button |
|---|---|
| No queue for the button | Short flash, nothing plays |
| Queue tagged for button 6 only | Button 6 plays. Buttons 1 to 5 do nothing |
| `PressType` right, but name without the `_#~` suffix | Short flash, nothing plays |
| Exact name and `PressType` on all six | **All six play** |
| Same, after a power cycle | Light stays on a few seconds, then silence (queue is now empty) |

The `Source` value doesn't matter: both `CustomRadio` and `Linkplay Radio` worked.

The name and track requirements are tested directly. `PressType` is an inference: every working queue had it set, but we never tried an exact name with the wrong `PressType`.

### Creating the queue

Use `PlayQueue#AppendQueue`, with this as `QueueContext` (XML‑escaped):

```xml
<?xml version="1.0"?>
<PlayList>
  <ListName>EXACT KEY NAME</ListName>
  <ListInfo>
    <SourceName>CustomRadio</SourceName>
    <Radio>1</Radio>
    <MarkSearch>0</MarkSearch>
    <TrackNumber>1</TrackNumber>
    <Quality>0</Quality>
    <UpdateTime>0</UpdateTime>
    <LastPlayIndex>1</LastPlayIndex>
    <RealIndex>0</RealIndex>
    <SwitchPageMode>0</SwitchPageMode>
    <CurrentPage>0</CurrentPage>
    <TotalPages>0</TotalPages>
    <searching>0</searching>
    <PressType>1</PressType>
    <Volume>0</Volume>
  </ListInfo>
  <Tracks>
    <Track1>
      <URL>STREAM URL</URL>
      <Metadata>...the key's Metadata...</Metadata>
      <Source>CustomRadio</Source>
      <ChapterNumber>0</ChapterNumber>
      <Chapters></Chapters>
    </Track1>
  </Tracks>
</PlayList>
```

Queue actions:

| Action | Behaviour |
|---|---|
| `AppendQueue` | Adds a queue and keeps the others. Use this |
| `CreateQueue` | Replaced the only queue when one existed, but left others in place later. Unpredictable, so avoid it |
| `DeleteQueue` | Removes a queue by name |
| `BrowseQueue` | Reads one queue by name. `TotalQueue` lists them all |
| `PlayQueueWithIndex` | Plays a queue by exact name, `Index` 1. Works for HLS too, and reports the real name and URL |
| `BackUpQueue` | **Don't use it.** It returned error 501 and deleted the queue |

`PlayQueueWithIndex` with `KeyMappingQueue` fails with 501. That isn't a queue you can play.

### After a restart

Queues survive a power cycle with their names and `PressType`, but `TrackNumber` becomes 0 and the track is gone. Checking only the name and `PressType` makes a broken queue look healthy, so check for a `<URL>` too.

To repair: `DeleteQueue`, then `AppendQueue`. Only one attempt at making tracks survive a restart was made (`BackUpQueue`), so a better answer may exist.

## Open questions

Good places to contribute:

- Can queue tracks survive a restart some other way?
- What exactly does the official app send when it plays a preset? Capturing it would settle this.
- Do other models and firmware versions behave the same?
- Do Spotify or TIDAL presets need queues too? The app leaves them alone.
- How does first‑time Wi‑Fi setup work on this firmware? We didn't investigate it.
