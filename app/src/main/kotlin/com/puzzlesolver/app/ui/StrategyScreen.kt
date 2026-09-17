package com.puzzlesolver.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puzzlesolver.core.puzzle.strategy.BoardState
import com.puzzlesolver.core.puzzle.strategy.Cell
import com.puzzlesolver.core.puzzle.strategy.Direction
import com.puzzlesolver.core.puzzle.strategy.Hit
import com.puzzlesolver.core.puzzle.strategy.Lane
import com.puzzlesolver.core.puzzle.strategy.RedRule
import com.puzzlesolver.core.puzzle.strategy.ShotGroup
import com.puzzlesolver.core.puzzle.strategy.StrategyPlan
import com.puzzlesolver.core.puzzle.strategy.StrategyStage
import com.puzzlesolver.core.puzzle.strategy.TeamPlan
import com.puzzlesolver.core.puzzle.strategy.TeamPlanner

/**
 * The Strategy room, as a reference card rather than a scan.
 *
 * Nothing here looks through the camera. The room's stages are fixed and transcribed,
 * so the only thing worth showing is the answer: the wall, each player's tiles in that
 * player's colour and numbered in the order they press them, and which presses have to
 * wait for another player's. (The clockwise numbering the solver plans in never reaches
 * the screen -- at the wall a player wants "my third", not "tile 11".) It is read
 * on a phone in one hand while the other hand presses tiles, which drives the layout:
 * the level is a row of big buttons, one stage fills the screen, the next stage is a
 * swipe up, the board stays put while the presses scroll under it, and every press is
 * a chip big enough to hit with a thumb.
 *
 * The room is played by a team, so the first thing asked is how many. The stage is then
 * split into a lane per player -- each a stretch of wall and a sequence of presses --
 * so that independent parts of the puzzle happen at the same time, and a press that has
 * to wait for another player's says whose.
 *
 * Tapping a chip shows the board as it stands once everything that press waits on has
 * landed, with the shot drawn to where it lands: how a player who has lost their place
 * finds it again.
 */
@Composable
fun StrategyScreen(
    levels: List<Int>,
    plans: Map<Int, List<StrategyPlan>>,
    level: Int,
    onSelectLevel: (Int) -> Unit,
    players: Int?,
    onSelectPlayers: (Int) -> Unit,
) {
    // Opaque and inset, unlike the HUD: there is no camera behind this to keep in view,
    // and the level row has to sit below the status bar or the clock lands on it.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E11))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        var changingPlayers by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Strategy", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Spacer(Modifier.width(10.dp))
                if (players != null) {
                    Box(
                        Modifier
                            .background(Color(0xFF1B222A), RoundedCornerShape(8.dp))
                            .clickable { changingPlayers = true }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("$players players", color = Color(0xFFDDE5EC), fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                // Dragging, not the edge: on a phone with gesture navigation the edge is Back.
                Text("drag right for other game modes", color = Color(0xFF6F7B87), fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            LevelPicker(levels, level, onSelectLevel)
            Spacer(Modifier.height(8.dp))

            val stages = plans[level]
            if (stages == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFFFF8C00))
                        Spacer(Modifier.height(10.dp))
                        Text("working out level $level", color = Color(0xFFB9C4CF), fontSize = 13.sp)
                    }
                }
            } else {
                // One pager per level, so switching level starts at its first stage rather
                // than at whatever page number the last level happened to be on.
                val pager = rememberPagerState(pageCount = { stages.size })
                LaunchedEffect(level) { pager.scrollToPage(0) }
                VerticalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize(),
                    key = { "$level-$it" },
                ) { page ->
                    StagePage(stages[page], players ?: 1)
                }
            }
        }
        if (players == null || changingPlayers) {
            PlayerPrompt(current = players) {
                onSelectPlayers(it)
                changingPlayers = false
            }
        }
    }
}

/**
 * Asked once, first thing, because everything on the page depends on the answer, and
 * asked as a full-screen sheet rather than a dialog because the answer is one thumb
 * press and the sheet is the biggest target for it.
 */
@Composable
private fun PlayerPrompt(current: Int?, onPick: (Int) -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF00B0E11)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 32.dp)) {
            Text("How many players?", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Each stage is split between you, so the parts that do not depend on " +
                    "each other get pressed at the same time by different people.",
                color = Color(0xFF9AA6B2),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (n in 2..5) {
                    val on = n == current
                    Box(
                        Modifier
                            .size(64.dp)
                            .background(if (on) Color(0xFF4C8DFF) else PLAYER_COLOURS[n - 1], RoundedCornerShape(16.dp))
                            .clickable { onPick(n) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$n", color = Color(0xFF06121F), fontWeight = FontWeight.Bold, fontSize = 26.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelPicker(levels: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    // All ten in one row whatever the width: a level that has to be scrolled to is a
    // level that gets missed with a phone in one hand.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (n in levels) {
            val on = n == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(
                        if (on) Color(0xFF4C8DFF) else Color(0xFF1B222A),
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelect(n) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$n",
                    color = if (on) Color(0xFF06121F) else Color(0xFFDDE5EC),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }
        }
    }
}

/**
 * One stage: the board, fixed, then the lanes scrolling under it.
 *
 * The board redraws for the selected chip. With nothing selected it shows the start of
 * the stage, which is the picture the team sees when they walk up to the wall.
 */
@Composable
private fun StagePage(plan: StrategyPlan, players: Int) {
    val stage = plan.stage
    val planner = remember(plan) { TeamPlanner(plan) }
    val team = remember(plan, players) { planner.schedule(players) }
    var selected by remember(plan, players) { mutableIntStateOf(-1) }
    val state = remember(plan, selected) {
        if (selected < 0) plan.states[0] else plan.stateAfter(planner.ancestors(selected))
    }
    val frame = when {
        selected >= 0 -> laneGroupOf(team, selected).frame
        else -> team.lanes.firstOrNull { it.groups.isNotEmpty() }?.groups?.first()?.frame ?: 0
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Sized to the widest panel, capped so a small stage does not become a poster,
        // and capped again so the board leaves the lower part of the page to the lanes.
        val widest = stage.panels.maxOf { it.last - it.first + 1 }
        val rows = stage.height * stage.panels.size
        val cell = minOf(
            maxWidth / widest,
            if (stage.panels.size > 1) 28.dp else 36.dp,
            (maxHeight * 0.55f - 8.dp * (stage.panels.size - 1)) / rows,
        )
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "Level ${stage.level}  ·  stage ${stage.index} of ${stage.stageCount}",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.weight(1f))
                if (stage.index < stage.stageCount) {
                    Text("swipe up for stage ${stage.index + 1}", color = Color(0xFF6F7B87), fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(6.dp))

            Board(
                plan = plan,
                team = team,
                state = state,
                frame = frame,
                highlight = if (selected >= 0) selected else null,
                cell = cell,
            )

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = Color(0xFF1B222A))

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 6.dp),
            ) {
                StageNotes(plan, team)
                for (lane in team.lanes) {
                    LaneSection(plan, planner, team, lane, selected, onSelect = { selected = if (selected == it) -1 else it })
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    "${stage.guns.size} tiles, ${plan.shots.size} presses  ·  transcription: ${stage.source}",
                    color = Color(0xFF6F7B87),
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun laneGroupOf(team: TeamPlan, shot: Int): ShotGroup {
    val lane = team.lanes.first { shot in it.shots }
    val i = lane.shots.indexOf(shot)
    return lane.groups.first { i in it.first..it.last }
}

@Composable
private fun StageNotes(plan: StrategyPlan, team: TeamPlan) {
    val stage = plan.stage
    val notes = ArrayList<String>()
    val idle = team.lanes.filter { it.shots.isEmpty() }
    if (idle.isNotEmpty()) {
        val who = idle.joinToString { "player ${it.player}" }
        notes += "No press for $who this stage: adding them would only make someone wait."
    }
    if (stage.isTimed) {
        val seconds = stage.periodMillis / 1000.0
        val what = if (stage.movingTargets != null) "The targets move" else "The reds move"
        val every = if (seconds >= 1) "%.0f s".format(seconds) else "%.1f s".format(seconds)
        notes += "$what every $every. Press each run of tiles while the board looks like its picture."
    } else if (stage.reds != null) {
        notes += "The reds do not move."
    }
    if (plan.redHits > 0) {
        val presses = plan.shots.indices.filter { plan.shots[it].hit == Hit.RED }
            .joinToString { "player ${team.playerOf(it)}'s ${team.steps[it]}" }
        notes += "The red chip ($presses) fires into a red on purpose and costs a life -- there is " +
            "no way round it. It clears the red for the press after."
    }
    if (stage.mirrors.isNotEmpty()) {
        notes += "Purple tiles put a new target on the other side, in the mirror-image column."
    }
    if (stage.gunsBlock) {
        notes += "A shot stops at the first orange tile in its way and takes it out."
    }
    if (stage.redRule == RedRule.FAIL && stage.reds != null) {
        notes += "Firing into a red fails the wave here, so the timing matters."
    }
    for (n in notes) {
        Text(n, color = Color(0xFFB9C4CF), fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
    }
}

/**
 * One player's presses, in runs.
 *
 * On a timed stage each run gets a thumbnail of the board it needs, so the player can
 * match the wall against the picture before pressing anything. A chip that has to wait
 * for another player's press says which one, in that player's colour.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LaneSection(
    plan: StrategyPlan,
    planner: TeamPlanner,
    team: TeamPlan,
    lane: Lane,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val stage = plan.stage
    val colour = PLAYER_COLOURS[lane.player - 1]
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        Box(Modifier.size(12.dp).background(colour, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(8.dp))
        Text("Player ${lane.player}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        if (lane.shots.isEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text("nothing this stage", color = Color(0xFF6F7B87), fontSize = 12.sp)
        }
    }
    for (group in lane.groups) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(bottom = 6.dp)) {
            if (stage.isTimed) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val first = lane.shots[group.first]
                    Thumbnail(stage, group.frame, plan.stateAfter(planner.ancestors(first)))
                    Text(
                        if (lane.groups.size > 1) "wait for this" else "when it looks like this",
                        color = Color(0xFF9AA6B2),
                        fontSize = 10.sp,
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (i in group.first..group.last) {
                    val shot = lane.shots[i]
                    Chip(
                        plan = plan,
                        team = team,
                        shot = shot,
                        colour = colour,
                        selected = shot == selected,
                        waitsFor = lane.waitsFor[shot] ?: emptyList(),
                        onSelect = { onSelect(shot) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(
    plan: StrategyPlan,
    team: TeamPlan,
    shot: Int,
    colour: Color,
    selected: Boolean,
    waitsFor: List<Int>,
    onSelect: () -> Unit,
) {
    val red = plan.shots[shot].hit == Hit.RED
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(52.dp)) {
        Box(
            Modifier
                .size(width = 52.dp, height = 44.dp)
                .background(
                    when {
                        selected -> Color.White
                        red -> Color(0xFFE0262A)
                        else -> colour
                    },
                    RoundedCornerShape(10.dp),
                )
                .clickable { onSelect() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${team.steps[shot]}",
                color = Color(0xFF1A1200),
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
            )
        }
        // "after P1 2": the press on another lane this one waits for, in that lane's colour.
        for (w in waitsFor) {
            Text(
                "after P${team.playerOf(w)} ${team.steps[w]}",
                color = PLAYER_COLOURS[team.playerOf(w) - 1],
                fontSize = 10.sp,
            )
        }
    }
}

// --- Drawing ---------------------------------------------------------

/** One per player, in the order they are numbered; the chips and the board share them. */
private val PLAYER_COLOURS = listOf(
    Color(0xFFFF8C00),
    Color(0xFF33DD77),
    Color(0xFF4CC9F0),
    Color(0xFFF06292),
    Color(0xFFFFD166),
)

private val COLOUR_EMPTY = Color(0xFF1E262E)
private val COLOUR_LINE = Color(0xFF0B0E11)
private val COLOUR_WALL = Color(0xFF3A3F45)
private val COLOUR_GUN = Color(0xFFFF8C00)
private val COLOUR_GUN_SPENT = Color(0xFF5A4630)
private val COLOUR_TARGET = Color(0xFF2E6BFF)
private val COLOUR_MIRROR = Color(0xFF9B59B6)
private val COLOUR_RED = Color(0xFFE0262A)
private val COLOUR_LABEL = Color(0xFF1A1200)
private val COLOUR_LABEL_SPENT = Color(0xFFB9A98F)

/**
 * The board in [state] on [frame], every gun in its player's colour, the gun of
 * [highlight] ringed and its shot drawn to where it lands.
 *
 * Two separate panels are drawn one above the other. That is not how they hang on the
 * wall, but it is how they fit on a phone held upright, and the L/R numbers say which is
 * which. A single panel, however wide, is drawn as one piece because shots cross it.
 */
@Composable
private fun Board(plan: StrategyPlan, team: TeamPlan, state: BoardState, frame: Int, highlight: Int?, cell: Dp) {
    val stage = plan.stage
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (panel in stage.panels) {
            val cols = panel.last - panel.first + 1
            Panel(
                plan = plan,
                team = team,
                state = state,
                frame = frame,
                highlight = highlight,
                columns = panel,
                modifier = Modifier
                    .width(cell * cols)
                    .height(cell * stage.height),
            )
        }
    }
}

@Composable
private fun Panel(
    plan: StrategyPlan,
    team: TeamPlan,
    state: BoardState,
    frame: Int,
    highlight: Int?,
    columns: IntRange,
    modifier: Modifier,
) {
    val stage = plan.stage
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Gun index -> the player pressing it and their step number. A gun no lane presses
    // is a spare: drawn plain orange with no number, so nobody goes looking for it.
    val owner = remember(team) {
        IntArray(stage.guns.size) { -1 }.also { o ->
            for (lane in team.lanes) for (s in lane.shots) o[plan.shots[s].gun] = lane.player
        }
    }
    val stepOf = remember(team) {
        IntArray(stage.guns.size) { 0 }.also { o ->
            for (s in plan.shots.indices) o[plan.shots[s].gun] = team.steps[s]
        }
    }
    Canvas(modifier) {
        val cols = columns.last - columns.first + 1
        val cw = size.width / cols
        val ch = size.height / stage.height
        val reds = state.fixedReds + stage.redsAt(frame)
        val moving = stage.movingTargets?.let { m ->
            m.frames[frame % m.frames.size].filterKeys { it in state.movingTargets }.values.toSet()
        } ?: emptySet()
        val small = minOf(cw, ch)
        val style = TextStyle(fontSize = with(density) { (small * 0.42f).toSp() }, fontWeight = FontWeight.Bold)
        val styleLong = TextStyle(fontSize = with(density) { (small * 0.34f).toSp() }, fontWeight = FontWeight.Bold)

        for (y in 0 until stage.height) {
            for (x in columns) {
                val c = Cell(x, y)
                val left = (x - columns.first) * cw
                val top = y * ch
                val gunIndex = stage.guns.indexOfFirst { it.x == x && it.y == y }
                val live = gunIndex >= 0 && gunIndex in state.guns
                val fill = when {
                    c in reds -> COLOUR_RED
                    c in state.targets || c in moving -> COLOUR_TARGET
                    c in state.mirrors -> COLOUR_MIRROR
                    live && owner[gunIndex] > 0 -> PLAYER_COLOURS[owner[gunIndex] - 1]
                    live -> COLOUR_GUN
                    gunIndex >= 0 -> COLOUR_GUN_SPENT
                    else -> COLOUR_EMPTY
                }
                drawRect(fill, Offset(left, top), Size(cw, ch))
                drawRect(COLOUR_LINE, Offset(left, top), Size(cw, ch), style = Stroke(1.dp.toPx()))
                if (gunIndex >= 0 && c !in reds) {
                    val dir = stage.guns[gunIndex].direction
                    val ink = if (live) COLOUR_LABEL else COLOUR_LABEL_SPENT
                    drawArrow(dir, left, top, cw, ch, ink)
                    val label = if (stepOf[gunIndex] > 0) "${stepOf[gunIndex]}" else ""
                    val layout = measurer.measure(label, if (label.length > 2) styleLong else style)
                    // Nudged away from the arrow so a two-digit label clears it.
                    if (label.isNotEmpty()) drawText(
                        layout,
                        color = ink,
                        topLeft = Offset(
                            left + (cw - layout.size.width) / 2 - dir.dx * cw * 0.1f,
                            top + (ch - layout.size.height) / 2 - dir.dy * ch * 0.1f,
                        ),
                    )
                }
            }
        }

        if (highlight != null) {
            val shot = plan.shots[highlight]
            val g = stage.guns[shot.gun]
            if (g.x in columns) {
                val gx = (g.x - columns.first + 0.5f) * cw
                val gy = (g.y + 0.5f) * ch
                val end = shot.cell?.takeIf { it.x in columns }
                if (end != null) {
                    val ex = (end.x - columns.first + 0.5f) * cw
                    val ey = (end.y + 0.5f) * ch
                    drawLine(Color.White, Offset(gx, gy), Offset(ex, ey), strokeWidth = 3.dp.toPx())
                    drawRect(
                        Color.White,
                        Offset((end.x - columns.first) * cw, end.y * ch),
                        Size(cw, ch),
                        style = Stroke(3.dp.toPx()),
                    )
                }
                drawRect(
                    Color.White,
                    Offset((g.x - columns.first) * cw, g.y * ch),
                    Size(cw, ch),
                    style = Stroke(3.dp.toPx()),
                )
            }
        }
    }
}

/** A small triangle on the edge of a gun's cell, pointing the way it fires. */
private fun DrawScope.drawArrow(dir: Direction, left: Float, top: Float, cw: Float, ch: Float, colour: Color) {
    val s = minOf(cw, ch) * 0.16f
    val cx = left + cw / 2
    val cy = top + ch / 2
    val path = Path()
    when (dir) {
        Direction.UP -> { path.moveTo(cx, top + s * 0.4f); path.lineTo(cx - s, top + s * 1.6f); path.lineTo(cx + s, top + s * 1.6f) }
        Direction.DOWN -> { path.moveTo(cx, top + ch - s * 0.4f); path.lineTo(cx - s, top + ch - s * 1.6f); path.lineTo(cx + s, top + ch - s * 1.6f) }
        Direction.LEFT -> { path.moveTo(left + s * 0.4f, cy); path.lineTo(left + s * 1.6f, cy - s); path.lineTo(left + s * 1.6f, cy + s) }
        Direction.RIGHT -> { path.moveTo(left + cw - s * 0.4f, cy); path.lineTo(left + cw - s * 1.6f, cy - s); path.lineTo(left + cw - s * 1.6f, cy + s) }
    }
    path.close()
    drawPath(path, colour)
}

/** The board at the start of a run, small: reds and targets only, no numbers. */
@Composable
private fun Thumbnail(stage: StrategyStage, frame: Int, state: BoardState) {
    val cellDp = if (stage.width > 12) 4.dp else 7.dp
    Canvas(
        Modifier
            .width(cellDp * stage.width)
            .height(cellDp * stage.height)
            .border(1.dp, Color(0xFF39424C)),
    ) {
        val cw = size.width / stage.width
        val ch = size.height / stage.height
        val reds = state.fixedReds + stage.redsAt(frame)
        val moving = stage.movingTargets?.let { m ->
            m.frames[frame % m.frames.size].filterKeys { it in state.movingTargets }.values.toSet()
        } ?: emptySet()
        for (y in 0 until stage.height) for (x in 0 until stage.width) {
            val c = Cell(x, y)
            val fill = when {
                stage.isWall(x) -> COLOUR_WALL
                c in reds -> COLOUR_RED
                c in state.targets || c in moving -> COLOUR_TARGET
                c in state.mirrors -> COLOUR_MIRROR
                stage.guns.any { it.x == x && it.y == y } -> COLOUR_GUN_SPENT
                else -> COLOUR_EMPTY
            }
            drawRect(fill, Offset(x * cw, y * ch), Size(cw, ch))
        }
    }
}
