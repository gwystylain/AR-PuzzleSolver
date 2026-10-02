package com.puzzlesolver.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puzzlesolver.spell.LetterMask
import com.puzzlesolver.spell.LobbySummary
import com.puzzlesolver.spell.PlayerState
import com.puzzlesolver.spell.Rules
import com.puzzlesolver.spell.client.Link
import com.puzzlesolver.spell.client.Notice
import com.puzzlesolver.spell.client.Room
import com.puzzlesolver.spell.client.ServerAddress
import com.puzzlesolver.spell.client.SpellState
import kotlinx.coroutines.delay

/** The landing page's id for Spellinator; not a scanning mode, so not in the registry. */
const val SPELLINATOR_ID = "spellinator"

/**
 * Spellinator: everyone in the lobby types the letters they can see, and every phone shows
 * the words of the chosen length that can be spelt from all of them together.
 *
 * Two screens. Until you are in a lobby, the open lobbies and a button to host one. Once
 * in, from top to bottom: whose lobby and how good the link is, the word length (anyone
 * can change it, for everyone), everyone's letters, the words, and your own letters over a
 * keypad.
 *
 * The keypad is the app's own, not the phone's keyboard: what is typed is a string of
 * unrelated letters, which is exactly what autocorrect, prediction and auto-capitalisation
 * exist to "fix". A key also fires on touch-down rather than release, which is worth the
 * tens of milliseconds on a screen whose whole point is to be quick.
 */
@Composable
fun SpellScreen(
    state: SpellState,
    /** The server in use, or null if none has been set. */
    server: String?,
    onHost: () -> Unit,
    onJoin: (String) -> Unit,
    onLeave: () -> Unit,
    onType: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onLength: (Int) -> Unit,
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
            RoomView(room, state.link, onLeave, onType, onBackspace, onClear, onLength)
        } else {
            LobbyView(state, server, onHost, onJoin, onEditServer = { editingServer = true })
        }
        NoticeBanner(state.notice, onDismissNotice, Modifier.align(Alignment.TopCenter))
    }
    if (editingServer) {
        ServerDialog(server, onSetServer, onDone = { editingServer = false })
    }
}

// --- Not in a lobby ------------------------------------------------------

@Composable
private fun LobbyView(
    state: SpellState,
    server: String?,
    onHost: () -> Unit,
    onJoin: (String) -> Unit,
    onEditServer: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Spellinator", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
            Spacer(Modifier.weight(1f))
            if (server != null) LinkPill(state.link)
        }
        Spacer(Modifier.height(16.dp))

        if (server == null) {
            Text(
                "No server set. Spellinator needs one to put phones in a lobby together.",
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
        Column(Modifier.weight(1f)) {
            // A count, so one colour: the seats taken need not be the first ones, and P1's
            // orange on a lobby whose P1 has left would say otherwise.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (n in 1..Rules.MAX_PLAYERS) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(if (n <= lobby.players) ACCENT else KEY, CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("${lobby.length}-letter words", color = TEXT_DIM, fontSize = 12.sp)
        }
        Text(if (full) "Full" else "Join ›", color = ACCENT, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

// --- In a lobby ----------------------------------------------------------

@Composable
private fun RoomView(
    room: Room,
    link: Link,
    onLeave: () -> Unit,
    onType: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onLength: (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        // Sideways, the words and the keypad sit side by side rather than stacked, or the
        // keypad would leave the words a strip one line high.
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1.3f).fillMaxHeight()) {
                    RoomTop(room, link, onLeave, onLength)
                    Spacer(Modifier.height(8.dp))
                    WordsPanel(room, Modifier.weight(1f))
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                    MyLetters(room, onClear)
                    Spacer(Modifier.height(8.dp))
                    Keypad(room, onType, onBackspace)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                RoomTop(room, link, onLeave, onLength)
                Spacer(Modifier.height(8.dp))
                WordsPanel(room, Modifier.weight(1f))
                Spacer(Modifier.height(8.dp))
                MyLetters(room, onClear)
                Spacer(Modifier.height(8.dp))
                Keypad(room, onType, onBackspace)
            }
        }
    }
}

@Composable
private fun ColumnScope.RoomTop(room: Room, link: Link, onLeave: () -> Unit, onLength: (Int) -> Unit) {
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
            "Reconnecting. Keep typing: your letters go through when it is back.",
            color = AMBER,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    LengthPicker(room.length, onLength)
    Spacer(Modifier.height(8.dp))
    Players(room)
}

/** Two taps, so a stray thumb does not give up the seat. */
@Composable
private fun LeaveButton(onLeave: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(3_000)
            armed = false
        }
    }
    Box(
        Modifier
            .background(if (armed) RED else KEY, RoundedCornerShape(8.dp))
            .clickable {
                if (armed) onLeave() else armed = true
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(if (armed) "Tap to leave" else "Leave", color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun LengthPicker(length: Int, onLength: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Letters", color = TEXT_DIM, fontSize = 13.sp, modifier = Modifier.width(56.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (n in Rules.LENGTHS) {
                val on = n == length
                Box(
                    Modifier
                        .weight(1f)
                        .height(42.dp)
                        .background(if (on) ACCENT else KEY, RoundedCornerShape(10.dp))
                        .clickable { onLength(n) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$n",
                        color = if (on) INK else TEXT,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Players(room: Room) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (player in room.players) PlayerChip(player, isMe = player.id == room.me)
    }
}

@Composable
private fun PlayerChip(player: PlayerState, isMe: Boolean) {
    val colour = PLAYER_COLOURS[player.id - 1]
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .alpha(if (player.online) 1f else 0.45f)
            .background(CARD, shape)
            .then(if (isMe) Modifier.border(1.dp, colour, shape) else Modifier)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .background(colour, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Text("P${player.id}", color = INK, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            player.letters.ifEmpty { if (player.online) "--" else "offline" },
            color = if (player.letters.isEmpty()) TEXT_FAINT else TEXT,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun WordsPanel(room: Room, modifier: Modifier) {
    // Kept in place by position, not by word. Keyed by word, the grid holds on to whichever
    // word was at the top when the list is replaced, so a new list can open halfway down --
    // at ARTAL rather than AALII -- with nothing to say the start is above. A new length is
    // a new list altogether, so it starts again from the top.
    val grid = rememberLazyGridState()
    LaunchedEffect(room.length) { grid.scrollToItem(0) }
    Column(
        modifier
            .fillMaxWidth()
            .background(CARD, RoundedCornerShape(14.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val shown = room.words.size
            Text(
                when {
                    room.total == 0 -> "Words"
                    room.total > shown -> "First $shown of ${"%,d".format(room.total)} words"
                    room.total == 1 -> "1 word"
                    else -> "${room.total} words"
                },
                color = TEXT_DIM,
                fontSize = 13.sp,
            )
            Spacer(Modifier.weight(1f))
            // The words are the server's and lag a keypress by a round trip; this says so
            // rather than leaving an old list looking like the answer.
            if (!room.current) CircularProgressIndicator(Modifier.size(14.dp), color = ACCENT, strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(6.dp))
        when {
            room.everyone == 0 -> Hint("Type the letters you can see. Everyone's letters count, and any letter can be used more than once.")
            room.words.isEmpty() && room.current -> Hint("No ${room.length}-letter words from these letters.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = (room.length * 15 + 18).dp),
                state = grid,
                modifier = Modifier.fillMaxSize().alpha(if (room.current) 1f else 0.6f),
                contentPadding = PaddingValues(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(room.words) { word ->
                    Text(
                        word,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        letterSpacing = 1.sp,
                        modifier = Modifier
                            .background(KEY, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = TEXT_DIM, fontSize = 14.sp, modifier = Modifier.padding(4.dp))
}

@Composable
private fun MyLetters(room: Room, onClear: () -> Unit) {
    val colour = room.me?.let { PLAYER_COLOURS[it - 1] } ?: TEXT_DIM
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(40.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (room.letters.isEmpty()) {
                Text("Your letters", color = TEXT_FAINT, fontSize = 14.sp)
            }
            for (c in room.letters) {
                Box(
                    Modifier
                        .size(width = 26.dp, height = 34.dp)
                        .background(colour, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$c", color = INK, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .height(40.dp)
                .alpha(if (room.letters.isEmpty()) 0.4f else 1f)
                .border(1.dp, RED, RoundedCornerShape(10.dp))
                .clickable(enabled = room.letters.isNotEmpty(), onClick = onClear)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Clear all", color = RED, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

/**
 * QWERTY, because that is where thumbs already know the letters are. A key is lit in your
 * colour if it is one of your letters, and marked if someone else has it, so a glance shows
 * what the lobby has between you.
 */
@Composable
private fun Keypad(room: Room, onType: (Char) -> Unit, onBackspace: () -> Unit) {
    val mine = LetterMask.of(room.letters)
    val anyone = room.everyone
    val colour = room.me?.let { PLAYER_COLOURS[it - 1] } ?: ACCENT
    val full = room.letters.length >= Rules.MAX_LETTERS
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Every row adds up to ten key-widths, so all the letter keys are the same size.
        KeyRow {
            for (c in "QWERTYUIOP") LetterKey(c, mine, anyone, colour, full, onType)
        }
        KeyRow {
            Spacer(Modifier.weight(0.5f))
            for (c in "ASDFGHJKL") LetterKey(c, mine, anyone, colour, full, onType)
            Spacer(Modifier.weight(0.5f))
        }
        KeyRow {
            Spacer(Modifier.weight(1.5f))
            for (c in "ZXCVBNM") LetterKey(c, mine, anyone, colour, full, onType)
            Key("⌫", KEY_DARK, TEXT, enabled = room.letters.isNotEmpty(), weight = 1.5f, onPress = onBackspace)
        }
    }
}

@Composable
private fun KeyRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), content = content)
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.LetterKey(
    c: Char,
    mine: Int,
    anyone: Int,
    colour: Color,
    full: Boolean,
    onType: (Char) -> Unit,
) {
    val isMine = LetterMask.contains(mine, c)
    val isTheirs = !isMine && LetterMask.contains(anyone, c)
    Key(
        label = "$c",
        background = when {
            isMine -> colour
            isTheirs -> KEY_THEIRS
            else -> KEY
        },
        textColour = if (isMine) INK else TEXT,
        enabled = !full,
        weight = 1f,
        onPress = { onType(c) },
    )
}

/** Fires on touch-down, with a tick, and darkens while held. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.Key(
    label: String,
    background: Color,
    textColour: Color,
    enabled: Boolean,
    weight: Float,
    onPress: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val press by rememberUpdatedState(onPress)
    var held by remember { mutableStateOf(false) }
    Box(
        Modifier
            .weight(weight)
            .height(52.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .background(if (held) background.copy(alpha = 0.6f) else background, RoundedCornerShape(8.dp))
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
        Text(label, color = textColour, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}

// --- Shared pieces ---------------------------------------------------------

@Composable
private fun LinkPill(link: Link) {
    val (colour, text) = when (link) {
        is Link.Online -> {
            val ms = link.rttMillis
            when {
                ms == null -> GREEN to "Online"
                ms < 150 -> GREEN to "$ms ms"
                ms < 400 -> AMBER to "$ms ms"
                else -> RED to "$ms ms"
            }
        }
        Link.Connecting -> AMBER to "Connecting"
        is Link.Offline -> RED to "Reconnecting"
        Link.Idle -> TEXT_FAINT to "Offline"
    }
    Row(
        Modifier
            .background(CARD, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(colour, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = TEXT, fontSize = 12.sp)
    }
}

@Composable
private fun BigButton(text: String, colour: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .alpha(if (enabled) 1f else 0.6f)
            .background(colour, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = INK, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

/** A line across the top that clears itself. */
@Composable
private fun NoticeBanner(notice: Notice?, onDismiss: (Long) -> Unit, modifier: Modifier) {
    LaunchedEffect(notice?.id) {
        val id = notice?.id ?: return@LaunchedEffect
        delay(4_000)
        onDismiss(id)
    }
    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .padding(12.dp)
                .fillMaxWidth()
                .background(Color(0xFF2A2F36), RoundedCornerShape(12.dp))
                .clickable { notice?.let { onDismiss(it.id) } }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(notice?.text.orEmpty(), color = Color.White, fontSize = 14.sp)
        }
    }
}

/**
 * The one place the phone's own keyboard appears: an address is a word, and typed once.
 * Autocorrect is still off, since "duckdns" is not in anyone's dictionary.
 */
@Composable
private fun ServerDialog(server: String?, onSetServer: (String) -> String?, onDone: () -> Unit) {
    var text by remember { mutableStateOf(server?.let { ServerAddress.describe(it) } ?: "") }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Spellinator server") },
        text = {
            Column {
                Text(
                    "The address of the lobby server, e.g. spell.example.org. Leave it empty for " +
                        "the one built into the app.",
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        problem = null
                    },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = problem?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                problem = onSetServer(text)
                if (problem == null) onDone()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

private val BACKGROUND = Color(0xFF0B0E11)
private val CARD = Color(0xFF151A20)
private val KEY = Color(0xFF1F2730)
private val KEY_DARK = Color(0xFF2A333D)
/** A letter someone else has: lifted off the plain keys, but nowhere near a player colour. */
private val KEY_THEIRS = Color(0xFF3A4654)
private val INK = Color(0xFF06121F)
private val TEXT = Color(0xFFDDE5EC)
private val TEXT_DIM = Color(0xFF9AA6B2)
private val TEXT_FAINT = Color(0xFF6F7B87)
internal val SPELL_ACCENT = Color(0xFF2ED3C6)
private val ACCENT = SPELL_ACCENT
private val GREEN = Color(0xFF33DD77)
private val AMBER = Color(0xFFFFC53D)
private val RED = Color(0xFFFF5C5C)
