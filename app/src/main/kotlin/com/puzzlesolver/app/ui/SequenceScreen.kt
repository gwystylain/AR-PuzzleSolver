package com.puzzlesolver.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puzzlesolver.spell.LobbySummary
import com.puzzlesolver.spell.Rules
import com.puzzlesolver.spell.SequenceBoard
import com.puzzlesolver.spell.client.Link
import com.puzzlesolver.spell.client.Room
import com.puzzlesolver.spell.client.ServerAddress
import com.puzzlesolver.spell.client.SpellState
import kotlinx.coroutines.delay

/** The landing page's id for Sequence; like Spellinator, not a scanning mode, so not in the registry. */
const val SEQUENCE_ID = "sequence"

/**
 * Sequence: a board of the numbers on the room's walls, filled in by everyone in the lobby
 * and counted through by the host, lowest first.
 *
 * Spellinator's lobby -- the same server, host and join, seats kept across a dropped link --
 * with a board in place of the letters: a row of seven slots for each wall, north (the door),
 * east, south and west. Anyone taps any slot and types a number of one or two digits on a
 * dialer; a second digit enters it at once, OK enters a single one. The host has Count:
 * the first press lights the lowest number on the board, every press after lights the next
 * one up and shades the one before. Back takes a press back; Reset wipes the board.
 *
 * The board and the count are the server's, so every phone shows the same; a number typed
 * here shows here at once and on the others a round trip later.
 */
@Composable
fun SequenceScreen(
    state: SpellState,
    /** The server in use, Spellinator's, or null if none has been set. */
    server: String?,
    onHost: () -> Unit,
    onJoin: (String) -> Unit,
    onLeave: () -> Unit,
    onSetSlot: (Int, Int?) -> Unit,
    onCount: () -> Unit,
    onCountBack: () -> Unit,
    onReset: () -> Unit,
    onDismissNotice: (Long) -> Unit,
    /** Returns what is wrong with the address, or null once it is set. */
    onSetServer: (String) -> String?,
) {
    var editingServer by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(BACKGROUND)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        val room = state.room
        if (room != null) {
            BoardRoom(room, state.link, onLeave, onSetSlot, onCount, onCountBack, onReset)
        } else {
            Lobbies(state, server, onHost, onJoin, onEditServer = { editingServer = true })
        }
        NoticeBanner(state.notice, onDismissNotice, Modifier.align(Alignment.TopCenter))
    }
    if (editingServer) {
        ServerDialog(server, onSetServer, onDone = { editingServer = false })
    }
}

// --- Not in a lobby ------------------------------------------------------

@Composable
private fun Lobbies(
    state: SpellState,
    server: String?,
    onHost: () -> Unit,
    onJoin: (String) -> Unit,
    onEditServer: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sequence", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
            Spacer(Modifier.weight(1f))
            if (server != null) LinkPill(state.link)
        }
        Spacer(Modifier.height(16.dp))

        if (server == null) {
            Text(
                "No server set. Sequence needs one to put phones in a lobby together: the same one as Spellinator.",
                color = TEXT_DIM,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(12.dp))
            BigButton("Set the server", ACCENT, enabled = true, onClick = onEditServer)
            return@Column
        }

        BigButton(
            text = if (state.joining) "Opening a lobby" else "Host a lobby",
            colour = ACCENT,
            enabled = !state.joining,
            onClick = onHost,
        )
        Spacer(Modifier.height(22.dp))
        Text(
            "OPEN LOBBIES",
            color = TEXT_FAINT,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.lobbies.isEmpty()) {
                Text(
                    if (state.link is Link.Online) {
                        "None yet. Host one and it shows up here on everyone else's phone."
                    } else {
                        "Waiting for the server."
                    },
                    color = TEXT_DIM,
                    fontSize = 14.sp,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.lobbies, key = { it.id }) { lobby ->
                        LobbyRow(lobby, enabled = !state.joining) { onJoin(lobby.id) }
                    }
                }
            }
        }
        Text(
            "Server: ${ServerAddress.describe(server)}",
            color = TEXT_FAINT,
            fontSize = 12.sp,
            modifier = Modifier
                .clickable(onClick = onEditServer)
                .padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun LobbyRow(lobby: LobbySummary, enabled: Boolean, onJoin: () -> Unit) {
    val full = lobby.players >= Rules.MAX_PLAYERS
    Row(
        Modifier
            .fillMaxWidth()
            .background(CARD, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled && !full, onClick = onJoin)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .alpha(if (full) 0.5f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            lobby.id,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.width(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (n in 1..Rules.MAX_PLAYERS) {
                Box(Modifier.size(10.dp).background(if (n <= lobby.players) ACCENT else KEY, CircleShape))
            }
        }
        Spacer(Modifier.weight(1f))
        Text(if (full) "Full" else "Join ›", color = ACCENT, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

// --- In a lobby ----------------------------------------------------------

@Composable
private fun BoardRoom(
    room: Room,
    link: Link,
    onLeave: () -> Unit,
    onSetSlot: (Int, Int?) -> Unit,
    onCount: () -> Unit,
    onCountBack: () -> Unit,
    onReset: () -> Unit,
) {
    // The slot whose number is being typed, and the digits typed into it so far. Nothing is
    // sent until the number is complete: a first digit alone is not yet a number.
    var editing by remember(room.lobby) { mutableStateOf<Int?>(null) }
    var typed by remember(room.lobby) { mutableStateOf("") }
    val slots = room.slots.takeIf { it.size == SequenceBoard.SLOTS } ?: List(SequenceBoard.SLOTS) { null }

    fun close() {
        editing = null
        typed = ""
    }

    fun enter(slot: Int, value: Int?) {
        onSetSlot(slot, value)
        close()
    }

    val onSlot: (Int) -> Unit = {
        editing = it
        typed = ""
    }

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Sideways, the count sits beside the board rather than under it.
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1.5f).fillMaxHeight()) {
                        Top(room, link, onLeave)
                        Spacer(Modifier.height(8.dp))
                        Board(slots, room.counted, editing, typed, onSlot, Modifier.weight(1f))
                    }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                        CountPanel(room, slots, onCount, onCountBack, onReset)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Top(room, link, onLeave)
                    Spacer(Modifier.height(10.dp))
                    Board(slots, room.counted, editing, typed, onSlot, Modifier.weight(1f))
                    Spacer(Modifier.height(10.dp))
                    CountPanel(room, slots, onCount, onCountBack, onReset)
                }
            }
        }

        val slot = editing
        if (slot != null) {
            BackHandler { close() }
            Dialer(
                slot = slot,
                current = slots[slot],
                typed = typed,
                onDigit = { d ->
                    val number = typed + d
                    // Two digits are a whole number: in it goes, and the dialer closes.
                    if (number.length >= 2) enter(slot, number.toInt()) else typed = number
                },
                onBackspace = { typed = typed.dropLast(1) },
                onClear = { enter(slot, null) },
                onDone = { if (typed.isEmpty()) close() else enter(slot, typed.toInt()) },
                onDismiss = { close() },
            )
        }
    }
}

@Composable
private fun Top(room: Room, link: Link, onLeave: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Lobby ", color = TEXT_DIM, fontSize = 14.sp)
        Text(
            room.lobby,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.width(10.dp))
        val me = room.me
        Box(
            Modifier
                .background(if (me != null) PLAYER_COLOURS[me - 1] else KEY, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                if (me != null) "You: P$me" else "Rejoining",
                color = if (me != null) INK else TEXT_DIM,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        LinkPill(link)
        Spacer(Modifier.width(6.dp))
        LeaveButton(onLeave)
    }
    if (!room.seated) {
        Text(
            "Reconnecting. Keep typing: your numbers go through when it is back.",
            color = AMBER,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    Players(room)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Players(room: Room) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (player in room.players) {
            val colour = PLAYER_COLOURS[player.id - 1]
            val shape = RoundedCornerShape(8.dp)
            val note = listOfNotNull(
                "counts".takeIf { player.id == room.host },
                "offline".takeIf { !player.online },
            ).joinToString(" · ")
            Row(
                Modifier
                    .alpha(if (player.online) 1f else 0.45f)
                    .background(CARD, shape)
                    .then(if (player.id == room.me) Modifier.border(1.dp, colour, shape) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .background(colour, shape)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text("P${player.id}", color = INK, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                if (note.isNotEmpty()) {
                    Text(note, color = TEXT_DIM, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}

/** The walls, in the board's order, each with a note for the one that needs it. */
private val WALLS = listOf("North" to "door", "East" to null, "South" to null, "West" to null)

/**
 * A row of slots per wall, each under a line naming its wall, so the slots have the whole
 * width; the column numbers along the top let a slot be called out ("east 4"). Sized to
 * the space it is given, up to a comfortable tile, so it fits a phone held either way.
 */
@Composable
private fun Board(
    slots: List<Int?>,
    counted: Int?,
    editing: Int?,
    typed: String,
    onSlot: (Int) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val gap = 6.dp
        // Between one wall's slots and the next wall's name.
        val wallGap = 10.dp
        val numbers = 14.dp
        val wallLine = 18.dp
        val underLine = 3.dp
        val rows = SequenceBoard.ROWS
        val columns = SequenceBoard.COLUMNS
        val rowHeight = ((maxHeight - numbers - (wallLine + underLine + wallGap) * rows) / rows)
            .coerceBetween(40.dp, 84.dp)
        val slotWidth = (maxWidth - gap * (columns - 1)) / columns
        val fontSize = with(LocalDensity.current) { (minOf(rowHeight, slotWidth) * 0.5f).toSp() }
        Column(verticalArrangement = Arrangement.spacedBy(wallGap)) {
            Row(Modifier.height(numbers), horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (c in 1..columns) {
                    Text(
                        "$c",
                        color = TEXT_FAINT,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            for (row in 0 until rows) {
                Column(verticalArrangement = Arrangement.spacedBy(underLine)) {
                    val (wall, note) = WALLS[row]
                    Row(Modifier.height(wallLine), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            wall.uppercase(),
                            color = TEXT,
                            fontSize = 12.sp,
                            letterSpacing = 1.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (note != null) {
                            Text("  ·  $note", color = ACCENT, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Row(Modifier.height(rowHeight), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (column in 0 until columns) {
                            val i = row * columns + column
                            Slot(
                                value = slots[i],
                                counted = counted,
                                editing = editing == i,
                                typed = typed,
                                fontSize = fontSize,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                onTap = { onSlot(i) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Dp.coerceBetween(low: Dp, high: Dp): Dp = minOf(maxOf(this, low), high)

/**
 * One slot: empty, a number, the number the count is on (lit), or one it has passed
 * (shaded). While its number is being typed it is outlined and shows the digits so far.
 */
@Composable
private fun Slot(
    value: Int?,
    counted: Int?,
    editing: Boolean,
    typed: String,
    fontSize: TextUnit,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val lit = value != null && value == counted
    val shaded = value != null && counted != null && value < counted
    val background by animateColorAsState(
        when {
            lit -> ACCENT
            shaded -> SHADED
            value != null -> KEY
            else -> EMPTY
        },
        label = "slot",
    )
    val scale by animateFloatAsState(if (lit) 1.08f else 1f, label = "lit")
    val shape = RoundedCornerShape(10.dp)
    val text = when {
        editing && typed.isNotEmpty() -> typed
        value != null -> "$value"
        else -> ""
    }
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(background, shape)
            .then(
                when {
                    editing -> Modifier.border(2.dp, Color.White, shape)
                    value == null -> Modifier.border(1.dp, KEY, shape)
                    else -> Modifier
                },
            )
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = when {
                lit -> INK
                shaded -> TEXT_FAINT
                else -> Color.White
            },
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            modifier = Modifier.alpha(if (shaded) 0.55f else 1f),
        )
    }
}

/**
 * Where the count stands, for everyone; for the host, Count, Back and Reset. Count is big
 * and fires on touch-down, since it is pressed again and again while the room is played.
 */
@Composable
private fun CountPanel(
    room: Room,
    slots: List<Int?>,
    onCount: () -> Unit,
    onCountBack: () -> Unit,
    onReset: () -> Unit,
) {
    val at = room.counted
    val numbers = slots.filterNotNull()
    val finished = SequenceBoard.finished(slots, at)
    val lit = at?.takeIf { it in numbers }
    val upNext = SequenceBoard.next(slots, at)?.takeIf { it != at && it in numbers }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(CARD, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        numbers.isEmpty() -> "EMPTY BOARD"
                        finished -> "ALL COUNTED"
                        lit != null -> "NOW"
                        at != null -> "COUNTING"
                        else -> "NOT COUNTING YET"
                    },
                    color = TEXT_FAINT,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (numbers.isEmpty()) {
                    Text("Tap any slot to type a number you can see", color = TEXT_DIM, fontSize = 14.sp)
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            lit?.toString() ?: if (finished) "✓" else "--",
                            color = if (lit != null) ACCENT else TEXT,
                            fontWeight = FontWeight.Bold,
                            fontSize = 34.sp,
                        )
                        if (upNext != null) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "next $upNext",
                                color = TEXT_DIM,
                                fontSize = 15.sp,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                    }
                }
            }
            if (room.isHost) {
                ResetButton(enabled = numbers.isNotEmpty() || at != null, onReset = onReset)
            } else if (room.host > 0) {
                Text("P${room.host} counts", color = PLAYER_COLOURS[room.host - 1], fontSize = 13.sp)
            }
        }
        if (room.isHost) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PressButton(Modifier.width(78.dp).fillMaxHeight(), enabled = at != null, background = KEY, onPress = onCountBack) {
                    Text("‹ Back", color = TEXT, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                PressButton(
                    Modifier.weight(1f).fillMaxHeight(),
                    enabled = numbers.isNotEmpty() && !finished,
                    background = ACCENT,
                    onPress = onCount,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("COUNT", color = INK, fontWeight = FontWeight.Black, fontSize = 30.sp, letterSpacing = 4.sp)
                        Text(
                            when {
                                numbers.isEmpty() -> "type numbers in first"
                                at == null -> "start at ${SequenceBoard.next(slots, null)}"
                                finished -> "all counted"
                                upNext != null -> "next: $upNext"
                                else -> "shade the last"
                            },
                            color = INK.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/** Two taps, so a stray thumb does not wipe everyone's numbers. */
@Composable
private fun ResetButton(enabled: Boolean, onReset: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(3_000)
            armed = false
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(if (armed) RED else Color.Transparent, shape)
            .border(1.dp, RED, shape)
            .clickable(enabled = enabled) {
                if (armed) {
                    armed = false
                    onReset()
                } else {
                    armed = true
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            if (armed) "Tap to wipe" else "Reset",
            color = if (armed) Color.White else RED,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }
}

/** A button that fires on touch-down, with a tick, and darkens while held. */
@Composable
private fun PressButton(
    modifier: Modifier,
    enabled: Boolean,
    background: Color,
    onPress: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val press by rememberUpdatedState(onPress)
    var held by remember { mutableStateOf(false) }
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(if (held) background.copy(alpha = 0.7f) else background, RoundedCornerShape(16.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        press()
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        held = true
                        tryAwaitRelease()
                        held = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

// --- The dialer ----------------------------------------------------------

/** The letters a phone's keypad carries under each digit. */
private val T9 = mapOf(
    '2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL",
    '6' to "MNO", '7' to "PQRS", '8' to "TUV", '9' to "WXYZ",
)

/**
 * A phone's keypad, up from the bottom over a dimmed board, for one slot. A second digit
 * enters the number and closes it; OK enters a single digit. With nothing typed, the
 * bottom-left key empties the slot; once a digit is typed, it takes it back. A tap outside
 * closes it with the slot unchanged.
 */
@Composable
private fun Dialer(
    slot: Int,
    current: Int?,
    typed: String,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    val (wall, _) = WALLS[slot / SequenceBoard.COLUMNS]
    Box(
        Modifier
            .fillMaxSize()
            .background(SCRIM)
            .pointerInput(Unit) { detectTapGestures { dismiss() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        BoxWithConstraints(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
            val keyHeight = ((maxHeight - 150.dp) / 4).coerceBetween(40.dp, 64.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(CARD, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    // Taps on the panel itself, between the keys, are not taps outside it.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "$wall ${slot % SequenceBoard.COLUMNS + 1}",
                            color = TEXT,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (typed.isEmpty()) "Two digits go in at once; OK for one" else "One more digit, or OK",
                            color = TEXT_FAINT,
                            fontSize = 12.sp,
                        )
                    }
                    Text(
                        when {
                            typed.isNotEmpty() -> typed
                            current != null -> "$current"
                            else -> "--"
                        },
                        color = if (typed.isNotEmpty()) Color.White else TEXT_FAINT,
                        fontWeight = FontWeight.Bold,
                        fontSize = 40.sp,
                    )
                }
                for (keys in listOf("123", "456", "789")) {
                    DialRow {
                        for (c in keys) DialKey("$c", T9[c], keyHeight, onPress = { onDigit(c) })
                    }
                }
                DialRow {
                    if (typed.isNotEmpty()) {
                        DialKey("⌫", null, keyHeight, background = KEY_DARK, onPress = onBackspace)
                    } else {
                        DialKey(
                            "Clear",
                            null,
                            keyHeight,
                            background = KEY_DARK,
                            textColour = RED,
                            enabled = current != null,
                            onPress = onClear,
                        )
                    }
                    DialKey("0", null, keyHeight, onPress = { onDigit('0') })
                    DialKey("OK", null, keyHeight, background = ACCENT, textColour = INK, onPress = onDone)
                }
            }
        }
    }
}

@Composable
private fun DialRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** A dialer key: fires on touch-down, like Spellinator's keypad, with the digit's letters under it. */
@Composable
private fun RowScope.DialKey(
    label: String,
    letters: String?,
    height: Dp,
    background: Color = KEY,
    textColour: Color = Color.White,
    enabled: Boolean = true,
    onPress: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val press by rememberUpdatedState(onPress)
    var held by remember { mutableStateOf(false) }
    Box(
        Modifier
            .weight(1f)
            .height(height)
            .alpha(if (enabled) 1f else 0.35f)
            .background(if (held) background.copy(alpha = 0.6f) else background, RoundedCornerShape(12.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        press()
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        held = true
                        tryAwaitRelease()
                        held = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = textColour, fontWeight = FontWeight.Bold, fontSize = if (label.length > 1) 18.sp else 26.sp)
            if (letters != null) Text(letters, color = TEXT_FAINT, fontSize = 9.sp, letterSpacing = 1.sp)
        }
    }
}

// Spellinator's, so the online rooms read as one family; only the accent is Sequence's own.
private val BACKGROUND = Color(0xFF0B0E11)
private val CARD = Color(0xFF151A20)
private val KEY = Color(0xFF1F2730)
private val KEY_DARK = Color(0xFF2A333D)
/** An empty slot: below the plain keys, so the numbers stand out from the gaps. */
private val EMPTY = Color(0xFF12171D)
/** A number the count has passed. */
private val SHADED = Color(0xFF0E1216)
private val SCRIM = Color(0x99000000)
private val INK = Color(0xFF06121F)
private val TEXT = Color(0xFFDDE5EC)
private val TEXT_DIM = Color(0xFF9AA6B2)
private val TEXT_FAINT = Color(0xFF6F7B87)
private val AMBER = Color(0xFFFFC53D)
private val RED = Color(0xFFFF5C5C)
internal val SEQUENCE_ACCENT = Color(0xFF4DA8FF)
private val ACCENT = SEQUENCE_ACCENT
