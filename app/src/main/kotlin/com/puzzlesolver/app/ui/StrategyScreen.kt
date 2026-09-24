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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puzzlesolver.core.puzzle.strategy.BoardState
import com.puzzlesolver.core.puzzle.strategy.Cell
import com.puzzlesolver.core.puzzle.strategy.Direction
import com.puzzlesolver.core.puzzle.strategy.Hit
import com.puzzlesolver.core.puzzle.strategy.Lane
import com.puzzlesolver.core.puzzle.strategy.ShotGroup
import com.puzzlesolver.core.puzzle.strategy.StrategyPlan
import com.puzzlesolver.core.puzzle.strategy.StrategyStage
import com.puzzlesolver.core.puzzle.strategy.TeamPlan
import com.puzzlesolver.core.puzzle.strategy.TeamPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * A guide room -- Strategy, or Gridlock -- as a reference card rather than a scan.
 *
 * Nothing here looks through the camera. The room's stages are fixed and transcribed,
 * so the only thing worth showing is the answer: the wall, each player's tiles in that
 * player's colour and numbered in the order they press them, and which presses have to
 * wait for another player's. (The clockwise numbering the solver plans in never reaches
 * the screen -- at the wall a player wants "my third", not "tile 11".) It is read
 * on a phone in one hand while the other hand presses tiles, which drives the layout:
 * the level is a row of big buttons, one stage fills the screen, the next stage is a
 * swipe up, the board stays put while the presses scroll under it, the presses stay
 * scrolled to the same player from one stage to the next, and every press is a chip big
 * enough to hit with a thumb.
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
    title: String,
    /** Whether to draw a stage's wall columns, or butt the boards either side together. */
    showWalls: Boolean = true,
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
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
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
            }
            Spacer(Modifier.height(8.dp))
            LevelPicker(levels, level, onSelectLevel)
            Spacer(Modifier.height(8.dp))

            val stages = plans[level]
            // One planner per plan for as long as the screen is up, so a stage split once
            // is not split again on the way back to it -- the split is a search, and on a
            // big stage it takes a noticeable moment on a phone.
            val planners = remember { HashMap<StrategyPlan, TeamPlanner>() }
            fun plannerFor(plan: StrategyPlan) = planners.getOrPut(plan) { TeamPlanner(plan) }
            // Split every stage of the level in the background as soon as it is on screen,
            // in page order, so swiping on finds the next stage ready.
            LaunchedEffect(stages, players) {
                if (stages != null && players != null) {
                    val queue = stages.map { plannerFor(it) }
                    withContext(Dispatchers.Default) { for (p in queue) p.schedule(players) }
                }
            }
            // Where the lanes are scrolled to, carried from stage to stage and level to
            // level: a player who has scrolled down to their own lane finds it there
            // again on the next stage instead of scrolling down through everyone else's.
            val lanesAt = remember { mutableStateOf(LanesAt()) }
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
                    StagePage(stages[page], plannerFor(stages[page]), players ?: 1, showWalls, lanesAt, pager, page)
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("How many players?", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
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
 *
 * The lanes open at [lanesAt], and scrolling them here moves it for every other stage;
 * [page] is this stage's page in [pager].
 */
@Composable
private fun StagePage(
    plan: StrategyPlan,
    planner: TeamPlanner,
    players: Int,
    showWalls: Boolean,
    lanesAt: MutableState<LanesAt>,
    pager: PagerState,
    page: Int,
) {
    val stage = plan.stage
    // Recomputed whenever the player count changes, including while the page is on
    // screen -- and a split for another count is never shown, even for the frame
    // before the new one arrives.
    val split by produceState(planner.scheduled(players), planner, players) {
        value = planner.scheduled(players) ?: withContext(Dispatchers.Default) { planner.schedule(players) }
    }
    val team = split?.takeIf { it.players == players }
    if (team == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFFFF8C00))
                Spacer(Modifier.height(10.dp))
                Text(
                    "splitting level ${stage.level} stage ${stage.index} between $players players",
                    color = Color(0xFFB9C4CF),
                    fontSize = 13.sp,
                )
            }
        }
        return
    }
    var selected by remember(plan, players) { mutableIntStateOf(-1) }
    val state = remember(plan, selected) {
        if (selected < 0) plan.states[0] else plan.stateAfter(planner.ancestors(selected))
    }
    val frame = when {
        selected >= 0 -> laneGroupOf(team, selected).frame
        else -> team.lanes.firstOrNull { it.groups.isNotEmpty() }?.groups?.first()?.frame ?: 0
    }

    val lanes = remember(team) { lanesAt.value.position(team).let { (i, o) -> LazyListState(i, o) } }
    LaunchedEffect(lanes) {
        // Off screen -- sliding in, or just left -- keep up with the lanes being scrolled
        // on the stage that is on screen.
        launch {
            snapshotFlow { lanesAt.value.takeIf { pager.currentPage != page } }
                .filterNotNull()
                .collect { at ->
                    if (at != lanes.scrolledTo(team)) at.position(team).let { (i, o) -> lanes.requestScrollToItem(i, o) }
                }
        }
        // On screen, pass on where the lanes are left once a scroll of them stops. A
        // swipe that starts on the lanes runs them to their end before it reaches the
        // pager, so first see where the pager comes to rest: if the swipe turned the
        // page, it was not a scroll of these lanes, just a swipe that passed through them.
        while (true) {
            snapshotFlow { lanes.isScrollInProgress }.first { it }
            snapshotFlow { lanes.isScrollInProgress }.first { !it }
            snapshotFlow { pager.currentPageOffsetFraction }.first { abs(it) < 1e-3f }
            if (pager.currentPage == page) lanesAt.value = lanes.scrolledTo(team)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Always drawn as it hangs on the wall: two boards side by side with the wall
        // between them, never stacked, so every stage reads the same way round even
        // where the tiles come out small. Sized to the page's width, capped so the board
        // leaves the lower part of the page to the lanes and so a small stage does not
        // become a poster.
        val columns = (0 until stage.width).filter { showWalls || !stage.isWall(it) }
        val cell = minOf(maxWidth / columns.size, 36.dp, maxHeight * 0.55f / stage.height)
        Column(Modifier.fillMaxSize()) {
            Text(
                "Level ${stage.level}  ·  stage ${stage.index} of ${stage.stageCount}",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(6.dp))

            Board(
                plan = plan,
                team = team,
                state = state,
                frame = frame,
                highlight = if (selected >= 0) selected else null,
                columns = columns,
                cell = cell,
            )

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = Color(0xFF1B222A))

            LazyColumn(
                state = lanes,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 6.dp, bottom = 14.dp),
            ) {
                items(team.lanes, key = { it.player }) { lane ->
                    Column(Modifier.padding(bottom = 10.dp)) {
                        LaneSection(plan, planner, team, lane, selected, onSelect = { selected = if (selected == it) -1 else it })
                    }
                }
            }
        }
    }
}

private fun laneGroupOf(team: TeamPlan, shot: Int): ShotGroup {
    val lane = team.lanes.first { shot in it.shots }
    val i = lane.shots.indexOf(shot)
    return lane.groups.first { i in it.first..it.last }
}

/**
 * Where a stage's lanes are scrolled to, in terms that mean the same on another stage.
 *
 * Not a pixel offset: the lanes are a different length on every stage, so the same
 * offset lands on somebody else's presses. What carries over is the player whose lane
 * is at the top of the list and how far into it the list is scrolled -- or, once the
 * lane below shows more than is left of that one, the lane below, since that is the one
 * being read. A list scrolled all the way down opens scrolled down to the last lane
 * instead, since that is where the last player always has it.
 */
private data class LanesAt(val player: Int = 1, val offset: Int = 0, val atEnd: Boolean = false)

/** Where these lanes, showing [team], are scrolled to now. */
private fun LazyListState.scrolledTo(team: TeamPlan): LanesAt {
    if (canScrollBackward && !canScrollForward) return LanesAt(atEnd = true)
    val info = layoutInfo
    val top = info.visibleItemsInfo.firstOrNull { it.index == firstVisibleItemIndex } ?: return LanesAt()
    val next = info.visibleItemsInfo.firstOrNull { it.index == top.index + 1 }
    val topShows = top.size - firstVisibleItemScrollOffset
    val nextShows = next?.let { minOf(it.size, info.viewportEndOffset - it.offset) } ?: 0
    return if (next != null && nextShows > topShows) {
        LanesAt(team.lanes[next.index].player)
    } else {
        LanesAt(team.lanes[top.index].player, firstVisibleItemScrollOffset)
    }
}

/** This place as a lane index and an offset into it, for [team]'s lanes. */
private fun LanesAt.position(team: TeamPlan): Pair<Int, Int> {
    // The last lane at the top, which the list only gets as far towards as its bottom
    // allows -- and where the last lane is longer than the list is tall, the start of it.
    if (atEnd) return team.lanes.lastIndex to 0
    val i = team.lanes.indexOfFirst { it.player == player }
    // A player the team no longer has, after the count went down.
    return if (i < 0) team.lanes.lastIndex to 0 else i to offset
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
        Spacer(Modifier.width(8.dp))
        if (lane.shots.isEmpty()) {
            Text("nothing this stage", color = Color(0xFF6F7B87), fontSize = 12.sp)
        } else {
            // Sideways walking only: it is the part that takes time, and a number a
            // player can check against the wall.
            val guns = lane.shots.map { stage.guns[plan.shots[it].gun] }
            val walked = guns.zipWithNext { a, b -> abs(a.x - b.x) }.sum()
            Text(
                "${lane.shots.size} presses  ·  " + when (walked) { 0 -> "no walking"; 1 -> "walks 1 tile"; else -> "walks $walked tiles" },
                color = Color(0xFF6F7B87),
                fontSize = 12.sp,
            )
        }
    }
    for (group in lane.groups) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(bottom = 6.dp)) {
            if (stage.isTimed) {
                val first = lane.shots[group.first]
                Thumbnail(stage, group.frame, plan.stateAfter(planner.ancestors(first)))
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
        // Only the latest per player: each presses in order, so waiting for someone's 2
        // is already waiting for their 1.
        val latest = waitsFor.groupBy { team.playerOf(it) }
            .mapValues { (_, ws) -> ws.maxOf { team.steps[it] } }
            .toSortedMap()
        for ((player, step) in latest) {
            Text(
                "after P$player $step",
                color = PLAYER_COLOURS[player - 1],
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
 * [highlight] ringed and its shot drawn to where it lands. The whole wall in one piece,
 * drawing only [columns] -- which leaves out the wall between two boards where it is not
 * part of what the team sees.
 */
@Composable
private fun Board(
    plan: StrategyPlan,
    team: TeamPlan,
    state: BoardState,
    frame: Int,
    highlight: Int?,
    columns: List<Int>,
    cell: Dp,
) {
    val stage = plan.stage
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Panel(
            plan = plan,
            team = team,
            state = state,
            frame = frame,
            highlight = highlight,
            columns = columns,
            modifier = Modifier
                .width(cell * columns.size)
                .height(cell * stage.height),
        )
    }
}

@Composable
private fun Panel(
    plan: StrategyPlan,
    team: TeamPlan,
    state: BoardState,
    frame: Int,
    highlight: Int?,
    columns: List<Int>,
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
    // Stage column -> column on screen, or -1 where it is not drawn.
    val slot = remember(columns) { IntArray(stage.width) { -1 }.also { a -> columns.forEachIndexed { i, x -> a[x] = i } } }
    Canvas(modifier) {
        val cw = size.width / columns.size
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
                val left = slot[x] * cw
                val top = y * ch
                if (stage.isWall(x)) {
                    drawRect(COLOUR_WALL, Offset(left, top), Size(cw, ch))
                    drawRect(COLOUR_LINE, Offset(left, top), Size(cw, ch), style = Stroke(1.dp.toPx()))
                    continue
                }
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
            if (slot[g.x] >= 0) {
                val gx = (slot[g.x] + 0.5f) * cw
                val gy = (g.y + 0.5f) * ch
                val end = shot.cell?.takeIf { slot[it.x] >= 0 }
                if (end != null) {
                    val ex = (slot[end.x] + 0.5f) * cw
                    val ey = (end.y + 0.5f) * ch
                    drawLine(Color.White, Offset(gx, gy), Offset(ex, ey), strokeWidth = 3.dp.toPx())
                    drawRect(
                        Color.White,
                        Offset(slot[end.x] * cw, end.y * ch),
                        Size(cw, ch),
                        style = Stroke(3.dp.toPx()),
                    )
                }
                drawRect(
                    Color.White,
                    Offset(slot[g.x] * cw, g.y * ch),
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
