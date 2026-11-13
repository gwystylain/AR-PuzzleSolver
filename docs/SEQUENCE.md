# Sequence

A team number room. Numbers are spread over the room's four walls; each player can see
some of them, and the team has to deal with them in order, lowest first. In the app,
everyone types the numbers they can see onto one shared board, and the host steps through
them: each press of **Count** lights the next number up and shades the one before, on
every phone at once.

It is **online**, on the same lobby server as [Spellinator](SPELLINATOR.md), and plays the
same way: one phone hosts, the rest join from the list.

## Playing

1. Everyone picks **Sequence** on the landing page.
2. One player taps **Host a lobby**. It appears on everyone else's phone as a four-letter
   code; they tap it to join. Players are P1 to P5 in the order they arrive. Sequence's
   lobbies are listed only in Sequence, and Spellinator's only in Spellinator.
3. The board is a row of seven slots for each wall, each under its wall's name: **North**
   (the door), **East**, **South** and **West**.
4. Anyone taps any slot, and a phone keypad comes up from the bottom. A **second digit
   enters the number** and closes it; **OK** enters a single digit. With nothing typed,
   **Clear** empties the slot; once a digit is typed, ⌫ takes it back. A tap outside, or
   Back, closes it with the slot unchanged. Numbers are 0 to 99, and a slot someone else
   filled can be changed like any other.
5. The **host** has **Count**, big, at the foot of the screen. The first press lights the
   lowest number on the board; every press after lights the next one up and shades the
   one before. After the last number one more press shades it too, and the board reads
   **All counted**. **‹ Back** takes a press back, for a tap too many.
6. **Reset** (the host's, two taps) wipes every number and the count, for the next round.
   **Leave** needs two taps, as in Spellinator.

Everyone sees where the count stands -- the number lit and the one after it -- above the
host's buttons; the rest see who counts in place of the buttons.

**The count is a number, not a place in a list.** Every slot holding it is lit and every
lower one shaded, so the board can change mid-count without the count losing its place: a
number typed below the count is shaded at once, one above it is still to come, and two
slots holding the same number light together.

Count, the slots and the dialer keys fire on touch-down, like Spellinator's keypad.

## How it works

Sequence is a lobby mode on the Spellinator server (`GameMode.SEQUENCE`), so it has
everything in *How it works* in [SPELLINATOR.md](SPELLINATOR.md): seats held for two minutes
across a dropped link, resumed with a token, the link indicator, the limits and the
security. What it adds:

| Phone sends | Meaning |
| --- | --- |
| `browse {game}` | `game` is `sequence`: list only Sequence's lobbies |
| `host {mode: sequence}` | Open a Sequence lobby |
| `slot {seq, slot, value}` | Slot `slot` (0-27, row by row: north, east, south, west) now holds `value` (0-99), or nothing when null. Anyone |
| `count {seq, at}` | The count now stands at `at` (0-100), or has not started when null. Host only |
| `reset {seq}` | Wipe the board and the count. Host only |

The `state` the server sends carries the board as `slots` (28 numbers or nulls) and the
count as `counted`.

- **Whole values, numbered**, like letters: "slot 5 is now 12", "the count is at 12", never
  "add" or "step on". A resend after a reconnect cannot count twice, and a change that
  arrives late is dropped by its `seq`.
- **The phone shows its own change at once.** A number typed, or a press of Count, is drawn
  straight away and sent; until the server's `ack` passes it, the phone keeps it and sends it
  again after a reconnect, as it does letters. Two phones changing one slot at once: the
  later to reach the server wins, on every phone.
- **The phone works out the next number** (`SequenceBoard.next` in `Protocol.kt`, shared
  with the server's tests) and sends the value. A count step the server refuses -- from a
  phone that is no longer the host -- is dropped rather than retried.
- **A server from before Sequence** knows neither the game nor the messages: it lists no
  Sequence lobbies, and hosting there opens a Spellinator lobby. The phone notices, leaves
  it, and says the server needs updating.

The phone's side is `SpellSession` with `game = Game.SEQUENCE`, in its own preferences
(`sequence`) so its seat never takes Spellinator's; the screen is `SequenceScreen`.

## Testing

`ProtocolTest` (the messages and `SequenceBoard`), `SequenceTest` (the lobby, against a fake
clock and no sockets) and two tests in `EndToEndTest` (two phones' real session code
against the real server, one of them through a link frozen mid-edit):

```bash
./gradlew :spell:test :spell-server:test
```
