package com.puzzlesolver.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.puzzlesolver.app.jigsaw.JigsawController
import com.puzzlesolver.core.puzzle.jigsaw.FloorColour
import com.puzzlesolver.core.puzzle.jigsaw.JigsawSolver
import com.puzzlesolver.core.puzzle.jigsaw.JigsawSolver.Cell
import com.puzzlesolver.spell.JigsawBoard
import com.puzzlesolver.spell.LobbySummary
import com.puzzlesolver.spell.Rules
import com.puzzlesolver.spell.client.Link
import com.puzzlesolver.spell.client.Room
import com.puzzlesolver.spell.client.ServerAddress
import com.puzzlesolver.spell.client.SpellState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The landing page's id for Jigsaw; like Sequence, not a scanning mode, so not in the registry. */
const val JIGSAW_ID = "jigsaw"

/**
 * Jigsaw: the floor room. Inside a green box on the floor are shapes in colours; outside it,
 * at either end of the room, are pieces in the same colours. Which pieces make each shape?
 *
 * Spellinator's lobby -- the same server, host and join, seats kept across a dropped link --
 * with three photos in place of letters: one of the box, one of the pieces at each end. Any
 * player takes any of them. Each is read on the phone that took it into a grid, shown over
 * the photo to be checked and touched up, and sent; every phone then works out, from the
 * three grids, which pieces make which shape -- turned as need be, never mirrored -- and
 * shows each shape with its pieces numbered, and the same numbers on the pieces where they
 * lie. Green is only ever the box: it finds the box, and is never a shape or a piece.
 */
@Composable
fun JigsawScreen(
    state: SpellState,
    /** The server in use, Spellinator's, or null if none has been set. */
    server: String?,
    photos: JigsawController.State,
    onHost: () -> Unit,
    onJoin: (String) -> Unit,
    onLeave: () -> Unit,
    onTakePhoto: (Int) -> Unit,
    onPickPhoto: (Int) -> Unit,
    /** Opens the editor on a region's grid as the lobby has it, without a photo. */
    onDraw: (Int, String?) -> Unit,
    /** A region is now this grid, or nothing read when null. */
    onSend: (Int, String?) -> Unit,
    onCloseDraft: () -> Unit,
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
            FloorRoom(room, state.link, onLeave, onTakePhoto, onPickPhoto, onDraw, onSend, onReset)
        } else {
            Lobbies(state, server, onHost, onJoin, onEditServer = { editingServer = true })
        }
        val draft = photos.draft
        if (draft != null && room != null) {
            BackHandler { onCloseDraft() }
            Editor(draft, onCancel = onCloseDraft, onSend = { cells ->
                onSend(draft.region, cells)
                onCloseDraft()
            })
        }
        if (photos.reading != null) Reading(photos.reading)
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
            Text("Jigsaw", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
            Spacer(Modifier.weight(1f))
            if (server != null) LinkPill(state.link)
        }
        Spacer(Modifier.height(16.dp))
        if (server == null) {
            Text(
                "No server set. Jigsaw needs one to put phones in a lobby together: the same one as Spellinator.",
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
        Text("OPEN LOBBIES", color = TEXT_FAINT, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
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
            modifier = Modifier.clickable(onClick = onEditServer).padding(vertical = 8.dp),
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

private val REGION_NAMES = listOf("Box", "Left", "Right")
private val REGION_HINTS = listOf("the shapes inside the green box", "the pieces at one end", "the pieces at the other end")

@Composable
private fun FloorRoom(
    room: Room,
    link: Link,
    onLeave: () -> Unit,
    onTakePhoto: (Int) -> Unit,
    onPickPhoto: (Int) -> Unit,
    onDraw: (Int, String?) -> Unit,
    onSend: (Int, String?) -> Unit,
    onReset: () -> Unit,
) {
    val regions = room.regions.takeIf { it.size == JigsawBoard.REGIONS } ?: List(JigsawBoard.REGIONS) { null }
    val by = room.regionBy.takeIf { it.size == JigsawBoard.REGIONS } ?: List(JigsawBoard.REGIONS) { null }
    var menuFor by remember(room.lobby) { mutableStateOf<Int?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Top(room, link, onLeave)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (r in 0 until JigsawBoard.REGIONS) {
                RegionCard(
                    region = r,
                    cells = regions[r],
                    by = by[r],
                    me = room.me,
                    modifier = Modifier.weight(1f),
                    onPhoto = { onTakePhoto(r) },
                    onMore = { menuFor = r },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Answer(regions)
        Spacer(Modifier.height(16.dp))
        if (room.isHost) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Next round:", color = TEXT_DIM, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                ResetButton(enabled = regions.any { it != null }, onReset = onReset)
            }
        } else if (room.host > 0) {
            Text("P${room.host} hosts, and wipes the floor for the next round.", color = TEXT_FAINT, fontSize = 12.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
    val r = menuFor
    if (r != null) {
        RegionMenu(
            region = r,
            has = regions[r] != null,
            onDismiss = { menuFor = null },
            onPhoto = { menuFor = null; onTakePhoto(r) },
            onPick = { menuFor = null; onPickPhoto(r) },
            onDraw = { menuFor = null; onDraw(r, regions[r]) },
            onClear = { menuFor = null; onSend(r, null) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
            "Reconnecting. Photos read meanwhile go through when it is back.",
            color = AMBER,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (player in room.players) {
            val colour = PLAYER_COLOURS[player.id - 1]
            val shape = RoundedCornerShape(8.dp)
            Row(
                Modifier
                    .alpha(if (player.online) 1f else 0.45f)
                    .background(CARD, shape)
                    .then(if (player.id == room.me) Modifier.border(1.dp, colour, shape) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.background(colour, shape).padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Text("P${player.id}", color = INK, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                val note = listOfNotNull("host".takeIf { player.id == room.host }, "offline".takeIf { !player.online })
                    .joinToString(" · ")
                if (note.isNotEmpty()) {
                    Text(note, color = TEXT_DIM, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}

/** One of the three photos: what was read from it, who read it, and the button to take it. */
@Composable
private fun RegionCard(
    region: Int,
    cells: String?,
    by: Int?,
    me: Int?,
    modifier: Modifier,
    onPhoto: () -> Unit,
    onMore: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .background(CARD, shape)
            .border(1.dp, if (cells == null) KEY else ACCENT.copy(alpha = 0.6f), shape)
            .clickable(onClick = onMore)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(REGION_NAMES[region].uppercase(), color = TEXT, fontSize = 13.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("⋯", color = TEXT_DIM, fontSize = 16.sp)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
            when {
                cells == null -> Text("No photo yet", color = TEXT_FAINT, fontSize = 12.sp)
                cells.isEmpty() -> Text("Nothing lit", color = TEXT_FAINT, fontSize = 12.sp)
                else -> FloorGrid(JigsawBoard.rows(cells), maxCell = 10.dp, gap = 1.dp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                by == null -> " "
                by == me -> "by you"
                else -> "by P$by"
            },
            color = if (by != null) PLAYER_COLOURS[by - 1] else TEXT_FAINT,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .background(if (cells == null) ACCENT else KEY, RoundedCornerShape(10.dp))
                .clickable(onClick = onPhoto)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (cells == null) "Photo" else "Retake",
                color = if (cells == null) INK else TEXT,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun RegionMenu(
    region: Int,
    has: Boolean,
    onDismiss: () -> Unit,
    onPhoto: () -> Unit,
    onPick: () -> Unit,
    onDraw: () -> Unit,
    onClear: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(CARD, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(REGION_NAMES[region], color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("A photo of ${REGION_HINTS[region]}.", color = TEXT_DIM, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            MenuItem("Take a photo", onPhoto)
            MenuItem("Choose a photo already taken", onPick)
            MenuItem(if (has) "Check or fix the grid by hand" else "Draw it by hand", onDraw)
            if (has) MenuItem("Clear it", onClear, colour = RED)
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit, colour: Color = TEXT) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(KEY, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Text(text, color = colour, fontSize = 15.sp)
    }
}

/** Two taps, so a stray thumb does not wipe everyone's photos. */
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
        Text(if (armed) "Tap to wipe" else "Reset", color = if (armed) Color.White else RED, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
private fun Reading(region: Int) {
    Box(
        Modifier
            .fillMaxSize()
            .background(SCRIM)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = ACCENT)
            Spacer(Modifier.height(12.dp))
            Text("Reading the ${REGION_NAMES[region].lowercase()} photo", color = TEXT, fontSize = 15.sp)
        }
    }
}

// --- The answer ------------------------------------------------------------

/**
 * Which pieces make which shape: a line per colour, the box with every shape's pieces
 * outlined and numbered, and both ends with the same numbers on the pieces to use. Worked
 * out here, on every phone, from the grids the lobby shares; it takes a millisecond, but
 * off the main thread all the same, since a grid read wildly wrong could take longer.
 */
@Composable
private fun Answer(regions: List<String?>) {
    val box = regions[JigsawBoard.BOX]
    val sides = listOf(regions[JigsawBoard.LEFT], regions[JigsawBoard.RIGHT])
    Text("ANSWER", color = TEXT_FAINT, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    if (box == null || sides.all { it == null }) {
        val waiting = listOfNotNull(
            "the box".takeIf { box == null },
            "the pieces".takeIf { sides.all { it == null } },
        ).joinToString(" and ")
        Text("Waiting for a photo of $waiting.", color = TEXT_DIM, fontSize = 14.sp)
        return
    }
    val solution by produceState<JigsawSolver.Solution?>(null, regions) {
        value = null
        value = withContext(Dispatchers.Default) {
            JigsawSolver.solve(JigsawBoard.rows(box), sides.map { it?.let(JigsawBoard::rows).orEmpty() })
        }
    }
    val s = solution
    if (s == null) {
        Text("Working it out…", color = TEXT_DIM, fontSize = 14.sp)
        return
    }
    if (s.answers.isEmpty()) {
        Text("No shapes in the box. Check its grid.", color = AMBER, fontSize = 14.sp)
        return
    }
    // Which of several ways is shown, per colour.
    val chosen = remember(regions) { mutableStateMapOf<Char, Int>() }
    // Numbers in the order the pieces sit in the box, colour by colour.
    val shown = s.answers.mapNotNull { a ->
        a.ways.getOrNull((chosen[a.colour] ?: 0).coerceIn(0, maxOf(a.ways.size - 1, 0)))
    }.flatten().sortedWith(compareBy({ order(it.piece.colour) }, { it.cells.minOf { c -> c.row * 100 + c.col } }))
    val number = shown.withIndex().associate { (i, p) -> p.piece.id to i + 1 }

    for (a in s.answers) {
        AnswerLine(a, chosen[a.colour] ?: 0, number, onNext = { chosen[a.colour] = ((chosen[a.colour] ?: 0) + 1) % a.ways.size })
        Spacer(Modifier.height(6.dp))
    }
    if (sides.any { it == null }) {
        val missing = REGION_NAMES[if (sides[0] == null) JigsawBoard.LEFT else JigsawBoard.RIGHT].lowercase()
        Text("Only one end's pieces are in: the $missing photo may hold the rest.", color = AMBER, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
    }
    Spacer(Modifier.height(6.dp))
    Text("BOX", color = TEXT_FAINT, fontSize = 11.sp, letterSpacing = 2.sp)
    Spacer(Modifier.height(4.dp))
    FloorGrid(
        rows = JigsawBoard.rows(box),
        maxCell = 34.dp,
        outlines = shown.map { p -> p.cells.toSet() to (number[p.piece.id]?.toString() ?: "") },
    )
    for ((k, side) in sides.withIndex()) {
        if (side == null || side.isEmpty()) continue
        Spacer(Modifier.height(14.dp))
        Text(REGION_NAMES[k + 1].uppercase(), color = TEXT_FAINT, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(4.dp))
        val used = shown.filter { it.piece.side == k }
        val usedCells = used.flatMap { it.piece.cells }.toSet()
        FloorGrid(
            rows = JigsawBoard.rows(side),
            maxCell = 26.dp,
            outlines = used.map { p -> p.piece.cells.toSet() to (number[p.piece.id]?.toString() ?: "") },
            dim = { it !in usedCells },
        )
    }
}

@Composable
private fun AnswerLine(answer: JigsawSolver.Answer, choice: Int, number: Map<Int, Int>, onNext: () -> Unit) {
    val colour = FloorColour.of(answer.colour)
    Row(
        Modifier
            .fillMaxWidth()
            .background(CARD, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).background(cellColour(answer.colour), RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(colour?.let(::colourName) ?: answer.colour.toString(), color = TEXT, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            val way = answer.ways.getOrNull(choice.coerceIn(0, maxOf(answer.ways.size - 1, 0)))
            Text(
                when {
                    way != null -> "Pieces " + way.mapNotNull { number[it.piece.id] }.sorted().joinToString(", ")
                    answer.pieces.isEmpty() -> "No ${colour?.let(::colourName)?.lowercase() ?: "such"} pieces read outside the box"
                    answer.gaveUp -> "Gave up looking: check the grids for a misread cell"
                    else -> "No set of pieces makes it: ${answer.target.size} cells, pieces of " +
                        answer.pieces.map { it.size }.sortedDescending().joinToString(", ")
                },
                color = if (way != null) ACCENT else AMBER,
                fontSize = 13.sp,
            )
        }
        if (answer.ways.size > 1) {
            Box(
                Modifier
                    .background(KEY, RoundedCornerShape(8.dp))
                    .clickable(onClick = onNext)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("${choice + 1} of ${answer.ways.size} ›", color = AMBER, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * A grid of the floor's cells, sized to the width it has up to [maxCell] a cell: lit cells
 * in their colour, the rest faint. Each of [outlines] -- a piece's cells and its number -- is
 * drawn round in white with its number on it; cells for which [dim] says so are faded.
 */
@Composable
private fun FloorGrid(
    rows: List<String>,
    maxCell: Dp,
    gap: Dp = 2.dp,
    outlines: List<Pair<Set<Cell>, String>> = emptyList(),
    dim: (Cell) -> Boolean = { false },
    onTap: ((Cell) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) return
    val cols = rows.maxOf { it.length }
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // As big as fits across, and down too where the space has a height.
        val across = (maxWidth - gap * (cols - 1)) / cols
        val down = if (constraints.hasBoundedHeight) (maxHeight - gap * (rows.size - 1)) / rows.size else maxCell
        val cell = minOf(maxCell, across, down)
        val width = cell * cols + gap * (cols - 1)
        val height = cell * rows.size + gap * (rows.size - 1)
        Canvas(
            Modifier
                .size(width, height)
                .then(
                    if (onTap == null) {
                        Modifier
                    } else {
                        // On a tap, not a press: the editor scrolls, and a scroll that starts
                        // on the grid must not paint the cell under the thumb.
                        Modifier.pointerInput(rows, cols) {
                            detectTapGestures(onTap = { o ->
                                val step = (cell + gap).toPx()
                                val c = (o.x / step).toInt()
                                val r = (o.y / step).toInt()
                                if (r in rows.indices && c in 0 until cols) onTap(Cell(c, r))
                            })
                        }
                    },
                ),
        ) {
            val s = cell.toPx()
            val g = gap.toPx()
            val step = s + g
            for ((r, row) in rows.withIndex()) {
                for (c in 0 until cols) {
                    val ch = row.getOrElse(c) { '.' }
                    val lit = ch != '.'
                    val fill = if (lit) cellColour(ch).copy(alpha = if (dim(Cell(c, r))) 0.25f else 1f) else EMPTY
                    drawRoundRect(fill, Offset(c * step, r * step), Size(s, s), CornerRadius(s * 0.15f))
                }
            }
            val stroke = maxOf(2f, s * 0.12f)
            for ((cells, label) in outlines) {
                val path = Path()
                for (cl in cells) {
                    val x = cl.col * step
                    val y = cl.row * step
                    // Only the sides with no cell of the same piece beyond them.
                    if (Cell(cl.col, cl.row - 1) !in cells) { path.moveTo(x - g / 2, y - g / 2); path.lineTo(x + s + g / 2, y - g / 2) }
                    if (Cell(cl.col, cl.row + 1) !in cells) { path.moveTo(x - g / 2, y + s + g / 2); path.lineTo(x + s + g / 2, y + s + g / 2) }
                    if (Cell(cl.col - 1, cl.row) !in cells) { path.moveTo(x - g / 2, y - g / 2); path.lineTo(x - g / 2, y + s + g / 2) }
                    if (Cell(cl.col + 1, cl.row) !in cells) { path.moveTo(x + s + g / 2, y - g / 2); path.lineTo(x + s + g / 2, y + s + g / 2) }
                }
                drawPath(path, Color.White, style = Stroke(width = stroke, cap = StrokeCap.Square))
                if (label.isNotEmpty() && s >= 12f) {
                    val at = cells.minWith(compareBy({ it.row }, { it.col }))
                    val text = measurer.measure(label, TextStyle(color = INK, fontSize = (s * 0.5f).toSp(), fontWeight = FontWeight.Bold))
                    val bg = s * 0.62f
                    val cx = at.col * step + s / 2
                    val cy = at.row * step + s / 2
                    drawRoundRect(Color.White, Offset(cx - bg / 2, cy - bg / 2), Size(bg, bg), CornerRadius(bg / 2))
                    drawText(text, topLeft = Offset(cx - text.size.width / 2f, cy - text.size.height / 2f))
                }
            }
        }
    }
}

// --- The editor ------------------------------------------------------------

/**
 * A region as read, over its photo, to check and touch up before it goes to the lobby.
 *
 * The photo has every cell read outlined in the colour it was read as, and what was left out
 * -- the green frame, anything on the wrong side of it -- in grey, so a misread shows at a
 * glance. Below it, the grid that will be sent, with room round it: pick a colour and tap a
 * cell to paint it, tap it again to clear it.
 */
@Composable
private fun Editor(draft: JigsawController.Draft, onCancel: () -> Unit, onSend: (String) -> Unit) {
    val blank = if (draft.region == JigsawBoard.BOX) BOX_SIZE else SIDE_SIZE
    val grid = remember(draft.id) { mutableStateListOf<String>().apply { addAll(padded(draft.rows, blank)) } }
    val counts = draft.rows.joinToString("").filter { it != '.' }.groupingBy { it }.eachCount()
    var brush by remember(draft.id) {
        mutableStateOf(counts.maxByOrNull { it.value }?.key ?: FloorColour.PIECES.first().code)
    }
    var problem by remember(draft.id) { mutableStateOf<String?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .background(BACKGROUND)
            .pointerInput(Unit) { detectTapGestures { } }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Cancel",
                color = TEXT_DIM,
                fontSize = 15.sp,
                modifier = Modifier.clickable(onClick = onCancel).padding(8.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(REGION_NAMES[draft.region], color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .background(ACCENT, RoundedCornerShape(10.dp))
                    .clickable {
                        val cells = trimmed(grid)
                        if (cells.size > JigsawBoard.MAX_SIDE || (cells.firstOrNull()?.length ?: 0) > JigsawBoard.MAX_SIDE) {
                            problem = "Too big to send: at most ${JigsawBoard.MAX_SIDE} cells each way."
                        } else {
                            onSend(cells.joinToString(JigsawBoard.ROW_BREAK.toString()))
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Send", color = INK, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        draft.photo?.let { photo ->
            PhotoWithMarks(photo, draft.marks)
            Spacer(Modifier.height(8.dp))
        }
        (problem ?: draft.message)?.let {
            Text(it, color = AMBER, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
        }
        Text(
            if (draft.photo != null) "Check the grid against the photo. Tap a cell to paint it, tap again to clear it." else "Tap a cell to paint it, tap again to clear it.",
            color = TEXT_DIM,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        Palette(brush) { brush = it }
        Spacer(Modifier.height(10.dp))
        FloorGrid(
            rows = grid.toList(),
            maxCell = 30.dp,
            onTap = { cell ->
                val row = grid[cell.row]
                val now = row[cell.col]
                val next = if (now == brush) '.' else brush
                grid[cell.row] = row.substring(0, cell.col) + next + row.substring(cell.col + 1)
                problem = null
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("Clear all") {
                val w = grid.firstOrNull()?.length ?: 0
                for (i in grid.indices) grid[i] = ".".repeat(w)
            }
            SmallButton("Wider") {
                for (i in grid.indices) grid[i] = "." + grid[i] + "."
            }
            SmallButton("Taller") {
                val w = grid.firstOrNull()?.length ?: 0
                grid.add(0, ".".repeat(w))
                grid.add(".".repeat(w))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(KEY, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, color = TEXT, fontSize = 13.sp)
    }
}

@Composable
private fun Palette(selected: Char, onSelect: (Char) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (colour in FloorColour.PIECES) {
            val on = colour.code == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(cellColour(colour.code), RoundedCornerShape(8.dp))
                    .border(if (on) 3.dp else 0.dp, if (on) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { onSelect(colour.code) },
                contentAlignment = Alignment.Center,
            ) {
                Text(colourName(colour).take(3), color = INK, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** The photo, fitted to the width, with every cell read outlined where it lies. */
@Composable
private fun PhotoWithMarks(photo: androidx.compose.ui.graphics.ImageBitmap, marks: List<JigsawController.Mark>) {
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = 420.dp), contentAlignment = Alignment.Center) {
        val aspect = photo.width.toFloat() / photo.height
        val w = minOf(maxWidth, maxHeight * aspect)
        Canvas(Modifier.size(w, w / aspect)) {
            val scale = size.width / photo.width
            drawImage(photo, dstSize = IntSize(size.width.toInt(), size.height.toInt()), dstOffset = IntOffset.Zero)
            for (m in marks) {
                val path = Path().apply {
                    moveTo(m.corners[0] * scale, m.corners[1] * scale)
                    for (k in 1 until 4) lineTo(m.corners[k * 2] * scale, m.corners[k * 2 + 1] * scale)
                    close()
                }
                if (m.kept) {
                    drawPath(path, Color.Magenta, style = Stroke(width = 3f))
                } else {
                    drawPath(path, Color(0x99FFFFFF), style = Stroke(width = 1.2f))
                }
            }
        }
    }
}

/** The grid with room round it to paint into: a blank one of [blank] when there is nothing yet. */
private fun padded(rows: List<String>, blank: Pair<Int, Int>): List<String> {
    if (rows.isEmpty()) return List(blank.second) { ".".repeat(blank.first) }
    val w = rows.maxOf { it.length }
    val margin = EDIT_MARGIN
    val edge = ".".repeat(w + margin * 2)
    return List(margin) { edge } + rows.map { ".".repeat(margin) + it.padEnd(w, '.') + ".".repeat(margin) } + List(margin) { edge }
}

/** The grid cut down to what is lit: the region as the lobby keeps it. Empty when nothing is. */
private fun trimmed(rows: List<String>): List<String> {
    val top = rows.indexOfFirst { r -> r.any { it != '.' } }
    if (top < 0) return emptyList()
    val bottom = rows.indexOfLast { r -> r.any { it != '.' } }
    val band = rows.subList(top, bottom + 1)
    val left = band.minOf { r -> r.indexOfFirst { it != '.' }.let { if (it < 0) Int.MAX_VALUE else it } }
    val right = band.maxOf { r -> r.indexOfLast { it != '.' } }
    return band.map { it.substring(left, right + 1) }
}

private fun order(code: Char): Int = FloorColour.PIECES.indexOfFirst { it.code == code }.let { if (it < 0) 99 else it }

private fun colourName(c: FloorColour): String = c.name.lowercase().replaceFirstChar { it.uppercase() }

/** A floor colour as drawn on the phone: the tile's colour, a little tamed. */
internal fun cellColour(code: Char): Color = when (code) {
    'W' -> Color(0xFFEDEDE0)
    'Y' -> Color(0xFFFFD447)
    'O' -> Color(0xFFFF944D)
    'R' -> Color(0xFFFF4D4D)
    'K' -> Color(0xFFFF6FC2)
    'P' -> Color(0xFFAA77FF)
    'B' -> Color(0xFF3D8BFF)
    'G' -> JIGSAW_ACCENT
    else -> EMPTY
}

/** A blank grid to draw into: the box's inside, and an end of the room. */
private val BOX_SIZE = 12 to 8
private val SIDE_SIZE = 16 to 8
private const val EDIT_MARGIN = 2

// Spellinator's, so the online rooms read as one family; only the accent is Jigsaw's own,
// the box's green.
private val BACKGROUND = Color(0xFF0B0E11)
private val CARD = Color(0xFF151A20)
private val KEY = Color(0xFF1F2730)
private val EMPTY = Color(0xFF1A2027)
private val SCRIM = Color(0x99000000)
private val INK = Color(0xFF06121F)
private val TEXT = Color(0xFFDDE5EC)
private val TEXT_DIM = Color(0xFF9AA6B2)
private val TEXT_FAINT = Color(0xFF6F7B87)
private val AMBER = Color(0xFFFFC53D)
private val RED = Color(0xFFFF5C5C)
internal val JIGSAW_ACCENT = Color(0xFF2EE6A6)
private val ACCENT = JIGSAW_ACCENT
