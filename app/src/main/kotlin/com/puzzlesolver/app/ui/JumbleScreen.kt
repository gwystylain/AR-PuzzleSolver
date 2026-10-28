package com.puzzlesolver.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puzzlesolver.spell.Rules
import com.puzzlesolver.spell.client.JumbleState

/** The landing page's id for Jumble; like Spellinator, not a scanning mode, so not in the registry. */
const val JUMBLE_ID = "jumble"

/**
 * Jumble: one player types the jumbled letters in front of them, and the screen shows every
 * word that uses all of them, each exactly once.
 *
 * Spellinator's screen, for one: no lobby, no word length to pick -- the word is as long as
 * the letters -- and no colours per player. From top to bottom: the link, the answer, the
 * letters typed, and the keypad, which is Spellinator's own for the same reasons (no
 * autocorrect, keys that fire on touch-down).
 *
 * The answer is the server's, since the word list lives only there (docs/SPELLINATOR.md),
 * so it follows a keypress by a round trip. Meanwhile the last answer stays, dimmed, with a
 * spinner in the corner, rather than the panel blanking between every keypress.
 */
@Composable
fun JumbleScreen(
    state: JumbleState,
    /** The server in use, Spellinator's, or null if none has been set. */
    server: String?,
    onType: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
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
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Sideways, the answer and the keypad sit side by side, as in Spellinator.
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1.3f).fillMaxHeight()) {
                        Header(state, server)
                        Spacer(Modifier.height(8.dp))
                        AnswerPanel(state, server, onEditServer = { editingServer = true }, Modifier.weight(1f))
                    }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                        Letters(state.letters, onClear)
                        Spacer(Modifier.height(8.dp))
                        Keypad(state.letters, onType, onBackspace)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Header(state, server)
                    Spacer(Modifier.height(8.dp))
                    AnswerPanel(state, server, onEditServer = { editingServer = true }, Modifier.weight(1f))
                    Spacer(Modifier.height(8.dp))
                    Letters(state.letters, onClear)
                    Spacer(Modifier.height(8.dp))
                    Keypad(state.letters, onType, onBackspace)
                }
            }
        }
    }
    if (editingServer) {
        ServerDialog(server, onSetServer, onDone = { editingServer = false })
    }
}

@Composable
private fun Header(state: JumbleState, server: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Jumble", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
        Spacer(Modifier.weight(1f))
        if (server != null) LinkPill(state.link)
    }
}

/** The last answer shown; a plain holder, since it only ever changes along with the state passed in. */
private class Shown(var state: JumbleState)

/**
 * Every word that uses all the letters: the first as big tiles, any others under it, all
 * alphabetical (`Lexicon.anagrams`). Most jumbles have one; some letters make several
 * words, and the screen cannot know which the room wants, so it shows them all.
 */
@Composable
private fun AnswerPanel(state: JumbleState, server: String?, onEditServer: () -> Unit, modifier: Modifier) {
    val last = remember { Shown(state) }
    if (state.current) last.state = state
    val shown = last.state
    val waiting = !state.current && server != null && !state.unsupported
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .fillMaxWidth()
            .background(CARD, shape)
            .then(if (shown.words.isNotEmpty()) Modifier.border(2.dp, ACCENT.copy(alpha = 0.6f), shape) else Modifier)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.alpha(if (waiting) 0.6f else 1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                server == null -> {
                    Hint("No server set. Jumble asks Spellinator's server for its words.")
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier
                            .height(46.dp)
                            .background(ACCENT, RoundedCornerShape(12.dp))
                            .clickable(onClick = onEditServer)
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Set the server", color = INK, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
                state.unsupported -> Hint("This server is older than Jumble and cannot answer it. It needs updating.")
                shown.letters.isEmpty() -> Hint(
                    "Type the jumbled letters, every one of them. The word uses them all, each once: " +
                        "a letter that is there twice is typed twice.",
                )
                shown.letters.length < Rules.MIN_LENGTH -> Hint("Keep going: every letter of the jumble.")
                // Only before the first answer of all; after that the last one stands in.
                !shown.current -> Hint("Waiting for the server.")
                shown.words.isEmpty() -> Hint(
                    "No word uses all ${shown.letters.length} of these letters. Check for one missed, or typed twice.",
                )
                else -> Answer(shown)
            }
        }
        if (waiting) {
            CircularProgressIndicator(
                Modifier.align(Alignment.TopEnd).size(14.dp),
                color = ACCENT,
                strokeWidth = 2.dp,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Answer(state: JumbleState) {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (state.total == 1) "THE WORD" else "${state.total} WORDS USE THEM ALL",
            color = TEXT_FAINT,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        WordTiles(state.words.first())
        if (state.words.size > 1) {
            Spacer(Modifier.height(14.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (word in state.words.drop(1)) {
                    Box(
                        Modifier
                            .background(KEY, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            word,
                            color = TEXT,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            letterSpacing = 2.sp,
                        )
                    }
                }
            }
        }
        if (state.total > state.words.size) {
            Spacer(Modifier.height(8.dp))
            Hint("and ${state.total - state.words.size} more")
        }
    }
}

/** The word as big tiles, shrinking to fit the longest. */
@Composable
private fun WordTiles(word: String) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val gap = if (word.length > 10) 3.dp else 6.dp
        val tile = minOf(56.dp, (maxWidth - gap * (word.length - 1)) / word.length)
        val fontSize = with(LocalDensity.current) { (tile * 0.6f).toSp() }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (c in word) {
                Box(
                    Modifier
                        .size(width = tile, height = tile * 1.2f)
                        .background(ACCENT, RoundedCornerShape(tile * 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$c", color = INK, fontWeight = FontWeight.Bold, fontSize = fontSize)
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = TEXT_DIM, fontSize = 14.sp, modifier = Modifier.padding(4.dp))
}

/** The letters as typed, how many, and Clear all for the next jumble. */
@Composable
private fun Letters(letters: String, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(40.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (letters.isEmpty()) {
                Text("Your letters", color = TEXT_FAINT, fontSize = 14.sp)
            }
            for (c in letters) {
                Box(
                    Modifier
                        .size(width = 24.dp, height = 34.dp)
                        .background(ACCENT, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$c", color = INK, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            }
        }
        if (letters.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text("${letters.length}", color = TEXT_DIM, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .height(40.dp)
                .alpha(if (letters.isEmpty()) 0.4f else 1f)
                .border(1.dp, RED, RoundedCornerShape(10.dp))
                .clickable(enabled = letters.isNotEmpty(), onClick = onClear)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Clear all", color = RED, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

/**
 * Spellinator's QWERTY. A key is lit once its letter is typed, with a count on it when it
 * has been typed more than once, since here repeats are part of the answer.
 */
@Composable
private fun Keypad(letters: String, onType: (Char) -> Unit, onBackspace: () -> Unit) {
    val full = letters.length >= Rules.MAX_JUMBLE_LETTERS
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Every row adds up to ten key-widths, so all the letter keys are the same size.
        KeyRow {
            for (c in "QWERTYUIOP") LetterKey(c, letters, full, onType)
        }
        KeyRow {
            Spacer(Modifier.weight(0.5f))
            for (c in "ASDFGHJKL") LetterKey(c, letters, full, onType)
            Spacer(Modifier.weight(0.5f))
        }
        KeyRow {
            Spacer(Modifier.weight(1.5f))
            for (c in "ZXCVBNM") LetterKey(c, letters, full, onType)
            Key("⌫", KEY_DARK, TEXT, enabled = letters.isNotEmpty(), weight = 1.5f, onPress = onBackspace)
        }
    }
}

@Composable
private fun RowScope.LetterKey(c: Char, letters: String, full: Boolean, onType: (Char) -> Unit) {
    val count = letters.count { it == c }
    Key(
        label = "$c",
        background = if (count > 0) ACCENT else KEY,
        textColour = if (count > 0) INK else TEXT,
        enabled = !full,
        weight = 1f,
        badge = if (count > 1) "$count" else null,
        onPress = { onType(c) },
    )
}

// Spellinator's, so the two word rooms read as one family; only the accent is Jumble's own.
private val BACKGROUND = Color(0xFF0B0E11)
private val CARD = Color(0xFF151A20)
private val KEY = Color(0xFF1F2730)
private val KEY_DARK = Color(0xFF2A333D)
private val INK = Color(0xFF06121F)
private val TEXT = Color(0xFFDDE5EC)
private val TEXT_DIM = Color(0xFF9AA6B2)
private val TEXT_FAINT = Color(0xFF6F7B87)
private val RED = Color(0xFFFF5C5C)
internal val JUMBLE_ACCENT = Color(0xFFA6E22E)
private val ACCENT = JUMBLE_ACCENT
