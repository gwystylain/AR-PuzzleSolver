# Spellinator

A team word room. Every player can see some letters; the answer is a word of a given
length spelt from all of them together. In the app, each player types the letters in
front of them on their own phone, anyone picks the word length, and every phone shows
every word of that length the team's letters can spell, as each letter is typed.

It is the one mode that is **online**. The phones meet in a lobby on a small server you
run yourself, on the NAS. Nothing else in the app touches the network, and this mode
talks only to that server.

## Playing

1. Everyone picks **Spellinator** on the landing page.
2. One player taps **Host a lobby**. The lobby appears on everyone else's phone at once,
   as a four-letter code such as `KQZT`, with a dot per player; they tap it to join. No
   accounts, no names: players are P1 to P5 in the order they arrive, in the same colours
   as the Strategy guide.
3. Nobody waits for anybody. Players can join and leave at any time, up to five; a
   player's letters count while they are in, and stop counting when they leave.
4. Each player types their letters on the app's own keypad. Repeats and order do not
   matter, and a letter can be used more than once in a word: T, O and P give TOP, POT,
   OPT, TOO, TOT, POP and the rest.
5. **Letters**, 3 to 8, sets the word length for everyone.
6. **Clear all** empties your own letters; ⌫ takes back the last one. **Leave** needs two
   taps, so a stray thumb does not give up the seat.

The words are listed alphabetically, the first 400 when there are more, with the total
above them. A small spinner beside the count means the list does not yet include your
latest keypress, which it will a round trip later.

On the keypad, your own letters are lit in your colour and letters someone else has are
raised, so a glance at it shows what the team has between you.

**Why the app's own keypad.** What is typed is a string of unrelated letters, exactly what
the phone's keyboard tries to correct, predict and capitalise into a word. The keypad also
fires on touch-down rather than on release.

## Why the server is on the NAS and not a phone

The obvious design is for the host's phone to be the server. It cannot work here: phones on
cellular sit behind their carrier's NAT and accept no incoming connections, so the other
phones would have nothing to connect to unless everyone was on the same Wi-Fi. A lobby on
one phone would also end when that phone lost its signal. A server on the NAS is reachable
from anywhere, outlives any phone dropping, and is the natural place for the word list to
live (see below).

## The word list

Collins Scrabble Words 2024 (CSW24): 280,887 words, of which the server loads the 120,018
of 3 to 8 letters. A lookup is a scan of one length's words against a 26-bit mask of the
team's letters. Even the longest, eight letters at 42,341 words, takes about 40 µs when
nearly every word matches, so the word list adds nothing measurable to an update.

**It is not in this repository, the APK or the server image, and must not be.** The list
is HarperCollins' copyright, licensed for private, non-commercial use, and the repository,
the APK and the image are all public. It lives only on the NAS, mounted read-only into the
container. A git-ignored copy sits at `spell-server/data/csw24.txt` for tests and local
runs.

The copy in use came from `csw24.txt` in
[chuck-sys/scrabble-checker-android](https://github.com/chuck-sys/scrabble-checker-android)
at commit `2ad82c76`: 2,838,540 bytes, SHA-256
`4200a40a888042ac4fa95d42ed3de82952301401df62c395180cdc2e67d63893`. It has the published
CSW24 word count exactly, all upper case A-Z, sorted, no duplicates. The authoritative
source is Collins' own **Collins Zyzzyva** (a free download from Collins, which includes
CSW24); any list with one word per line will do, and a list with a definition after each
word, as Zyzzyva exports, reads the same.

## How it works

```
phone ─┐
phone ─┼─ wss:// (TLS) ─► reverse proxy ─► spell-server
phone ─┘  cellular/Wi-Fi  (TLS, DuckDNS)   (Docker, on the NAS)
```

Three modules, all plain Kotlin on the JVM:

| Module | What it is |
| --- | --- |
| `spell/` | What both ends agree on: the messages (`Protocol.kt`), the limits (`Rules.kt`), and the word finder (`Lexicon.kt`). |
| `spell-client/` | The phone's connection: `SpellSession`. Plain JVM rather than Android, so it is tested against the real server. |
| `spell-server/` | The lobby server: Ktor on Netty, one WebSocket endpoint, lobbies in memory (`LobbyManager`). |

The app adds only `SpellController` (the network callback and where the seat is saved) and
`SpellScreen`.

### The protocol

JSON text frames on one WebSocket at `/v1/ws`, tagged by `t`. The version is in the path so
a future protocol can be served alongside this one.

| Phone sends | Meaning |
| --- | --- |
| `browse` | Send me the lobby list, now and on every change |
| `host` / `join {lobby}` | Seat me |
| `resume {lobby, player, token}` | Give me back my seat after a dropped connection |
| `letters {seq, letters}` | My letters are now these |
| `length {seq, length}` | Everyone's word length is now this |
| `leave`, `ping {at}` | |

| Server sends | Meaning |
| --- | --- |
| `lobbies` | The open lobbies, newest first |
| `joined {lobby, player, token, ack}` | You are seated; `token` takes the seat back later |
| `state` | The whole lobby: length, every player's letters, online and `ack`, and the words |
| `error {code}`, `left`, `pong {at}` | |

Two choices carry most of the weight:

- **The server sends whole state, never diffs.** A lobby is five short strings and a number,
  so a snapshot costs nothing, and a phone that missed messages while its link was down is
  right again after the next one. The largest possible update is under 5 KB.
- **A phone sends whole values, numbered.** "My letters are now TOP", not "add P". Sending
  one twice is harmless, and the number (`seq`) lets the server drop one that arrives late
  and lets the phone tell, from the `ack` the server echoes back, which of its changes the
  words on screen already include. That is what the spinner shows.

### Latency

The latency budget is the network, and nothing else is allowed into it:

- **Typing never waits.** A letter appears the instant the key is touched and is sent at
  once; only the word list waits for the server.
- **One round trip.** A keypress goes to the server, which applies it, looks the words up
  and sends the new state to every phone in the lobby, each encoded once.
- **No Nagle.** Every message is tiny and wants to leave now. With Nagle's algorithm on, a
  write can wait up to 200 ms for the previous one's ACK. TCP_NODELAY is set on the phone's
  sockets (`NoDelaySocketFactory`); Netty sets it on the server's.
- **No slow phone holds up the rest.** Each connection has its own short send queue. A
  phone so far behind that its queue fills is dropped, and catches up from one snapshot
  when it reconnects.

Measured over loopback, which leaves only the code's own cost, a keypress on one phone
shows on another in a **median of 1.1 ms** (p95 1.4 ms). On a real link everything else is
the round trip to the NAS, shown live in the lobby's corner. The link indicator turns
amber at 150 ms and red at 400 ms.

### When a phone drops

On cellular this is the normal case: walking between rooms, switching masts, or moving
from the venue's Wi-Fi to cellular. The design assumes it will happen mid-game.

- **The seat is held for two minutes.** A dropped player's letters still count, and for the
  first three seconds they still show as online, so a phone that switches networks and
  comes straight back does not flicker on anyone's screen.
- **Silence is noticed.** A phone that walks out of coverage leaves a TCP connection that
  never closes; it just goes quiet. The app pings every 2 s and gives up on a link that has
  said nothing for 6 s. The server closes a connection idle for 20 s, and Ktor's own
  protocol pings back that up.
- **Reconnecting starts at once.** The first retry has no delay, then 0.25, 0.5, 1, 2, 3 and
  5 s, with jitter. The backoff is capped at 5 s because the player is standing there
  waiting. Android's default-network callback cuts it short: a new network means a retry
  right now.
- **The seat comes back.** The phone presents its token and gets the same seat and number.
  The server may still think the old connection is up; the token wins, and the old
  connection is closed. Anything typed meanwhile is sent, oldest first. Changes the server
  has not acknowledged after a second on a live link are sent again; that only happens if
  the rate limit refused them, and stops the server's copy staying behind.
- **If the seat has gone** (more than two minutes away), the phone joins the same lobby
  afresh, says so, and sends its letters. If the lobby has gone too, it goes back to the
  list and says that.
- **If the app itself is killed** in the background, the seat, token and letters are in
  the app's private preferences, and reopening Spellinator takes the seat back.

All of that is tested end to end (`EndToEndTest`): the real server and the phone's real
connection code over real sockets, through `FlakyProxy`, which can cut every connection,
stall them silently without closing them, or point them at a restarted server. With the
test timings, a silent link is noticed and the seat is back in about 750 ms. With the
app's timings, measured on a phone with the server frozen mid-game, it was noticed after
5.5 s, and the phone was back in its seat a tenth of a second after the server answered
again. That 6 s is the price of not treating every cellular hiccup as a dead link.

### Security

The server faces the internet without accounts, so what it protects is the NAS and the
lobbies' integrity, not secrets. Letters are not sensitive.

- **TLS only.** The app refuses a `ws://` address, and its network security config
  forbids cleartext, with one exception: `localhost`, which never leaves the phone and is
  how a release build is tested over `adb reverse` (see *Testing*). A debug build allows
  cleartext anywhere, for a server on the same desk. TLS ends at the reverse proxy, and the
  container's port is reachable only from the LAN.
- **Seats are proved, not claimed.** Player numbers are public; each seat has a 128-bit
  random token from `SecureRandom`, compared in constant time and never logged. Lobby codes
  are not secrets: every lobby is listed to anyone browsing, by design.
- **Every message is validated** against a strict shape: letters A-Z only, at most 16;
  length 3 to 8; codes and tokens by pattern. Anything else is answered with an error, and
  twenty such answers close the connection. Frames over 1 KB are refused outright; no
  real message is over 200 bytes.
- **Limits everywhere.** 20 messages a second per connection (bursts of 40); 50 lobbies;
  250 connections, 20 from any one address. The per-address limit is not lower because a
  carrier puts many phones behind one address. Behind the proxy, the address is the last
  `X-Forwarded-For` entry, the one the proxy wrote, never one the client could supply.
- **No browsers.** The app sends no `Origin` header and a browser always does, so any
  connection with one is refused. A web page cannot use a visitor's browser to reach the
  server.
- **Nothing stored, nothing to leak.** Lobbies are in memory only. The server writes
  nothing to disk and logs lobby codes and seat numbers, never tokens.
- **A locked-down container.** Non-root (the NAS's apps user), read-only filesystem, every
  Linux capability dropped, no privilege escalation, 384 MB memory cap; the word list is
  mounted read-only. Netty is pinned past the 2025 fixes to its HTTP parsing.

## Deploying it

### 1. The image

`.github/workflows/spell-server.yml` tests the server and publishes
`ghcr.io/gwystylain/puzzlesolver-spell-server:latest` on every push to `main` that touches
it. If the package comes out private, as new ones usually do, make it public (GitHub → your
profile → Packages → puzzlesolver-spell-server → Package settings → Change visibility) so
TrueNAS can pull it without credentials. It contains no word list, so there is nothing in it
to protect.

### 2. TrueNAS

1. Create `/mnt/HDDs/Applications/Spellinator` and copy `csw24.txt` into it, readable by
   the apps user (568).
2. Apps → Discover Apps → Custom App → Install via YAML, and paste
   [`spell-server/truenas.yaml`](../spell-server/truenas.yaml). It publishes port 8096.
3. The log should open with `120018 words from csw24.txt`.

### 3. A hostname, and TLS

DuckDNS resolves any name under your subdomain, so `spellinator.<you>.duckdns.org` already
points home. In the reverse proxy that serves your other apps, add a proxy host for it:

- forward to `http://<NAS LAN address>:8096`;
- **WebSocket support on.** In Nginx Proxy Manager it is the *Websockets Support* switch.
  In plain nginx it is `proxy_http_version 1.1` with the `Upgrade` and `Connection`
  headers passed through;
- a Let's Encrypt certificate, with HTTP redirected to HTTPS;
- `X-Forwarded-For` set by the proxy. Nginx Proxy Manager does this already.

The proxy's idle timeout is no concern: the app pings every two seconds. Do **not** forward
port 8096 on the router.

### 4. Pointing the app at it

Set the address once, as a repository variable, and every APK CI builds has it:

```bash
gh variable set SPELL_SERVER_URL --repo gwystylain/AR-PuzzleSolver --body "wss://spellinator.<you>.duckdns.org"
```

A variable rather than a secret, since it is readable in the APK anyway; it just keeps the
address out of the source. Without it, the app asks for a server the first time
Spellinator is opened, and the address can be changed at any time from the foot of the
lobby list; saving it empty goes back to the address built into the app. For a local
build, put `spellServerUrl=wss://...` in `~/.gradle/gradle.properties`.

### 5. Checking it from outside

From any machine with the repo, against the real deployment:

```bash
./gradlew :spell-server:test --tests '*LiveServerTest*' -Dspell.live=spellinator.<you>.duckdns.org
```

It opens a lobby, joins it from a second connection, checks that T, O and P give TOP, and
prints the real keypress-to-other-phone time and ping from where it ran. Run from the house
it measures the hairpin through the router. Run from a laptop tethered to a phone, it
measures what the players will get.

## Configuration

Environment variables on the container; the defaults are what the YAML relies on.

| Variable | Default | |
| --- | --- | --- |
| `SPELL_DICTIONARY` | `/data/csw24.txt` | The word list |
| `SPELL_PORT` | `8080` | Inside the container |
| `SPELL_TRUST_FORWARDED` | `false` | Read the client address from `X-Forwarded-For`; set by the YAML |
| `SPELL_GRACE_SECONDS` | `120` | How long a dropped player's seat is held |
| `SPELL_MAX_LOBBIES` | `50` | |
| `SPELL_MAX_CONNECTIONS` | `250` | |
| `SPELL_MAX_CONNECTIONS_PER_ADDRESS` | `20` | |

## Testing

```bash
./gradlew :spell:test :spell-client:test :spell-server:test
```

- `LexiconTest`: the word finder, including the brief's example, and against the real
  CSW24 list when it is present (skipped in CI, where it is not).
- `ProtocolTest`: every message round-trips; junk and out-of-range values are refused.
- `LobbyManagerTest`: seats, the grace period, resuming and taking over, the caps, against
  a fake clock and no sockets.
- `EndToEndTest`: everything above, for real, through `FlakyProxy`; plus the server's
  defences tried from a raw socket.
- `LiveServerTest`: the deployed server, when pointed at one.

To run the server locally, from the repo:

```bash
./gradlew :spell-server:installDist
SPELL_DICTIONARY=spell-server/data/csw24.txt spell-server/build/install/spell-server/bin/spell-server
```

A debug build of the app can then use `ws://<this machine's LAN address>:8080`. A release
build can too, through USB rather than the LAN, since it allows cleartext to the phone
itself:

```bash
adb reverse tcp:8080 tcp:8080
```

and a server address of `ws://localhost:8080`. Save the address empty afterwards to go
back to the built-in one. That is how this mode was first tried on a phone: the server in
Docker on a laptop, extra players scripted with `SpellSession`, and the link cut with
`adb kill-server` and stalled with `docker pause`.
