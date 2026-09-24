package com.puzzlesolver.core.puzzle.strategy

import java.util.Locale
import java.util.zip.CRC32

/**
 * One stage's splits, a team size each, as bundled with the app.
 *
 * What is stored is the plan and each player's presses in order; the rest of a
 * [TeamPlan] -- steps, waits, timing -- is worked out from those when a team size is
 * first asked for, which takes a millisecond. It is the search that finds them that is
 * too slow for a phone.
 */
class StageSplits internal constructor(
    val stage: StrategyStage,
    internal val stored: Map<Int, Stored>,
) {
    /** A split as the file has it: the plan, and each player's presses as indices into it. */
    internal class Stored(val plan: StrategyPlan, val lanes: List<List<Int>>)

    /** The team sizes there is a split for. */
    val teamSizes: Set<Int> get() = stored.keys

    private val built = HashMap<Int, TeamPlan>()

    /** The split for [players] people. */
    @Synchronized
    fun forPlayers(players: Int): TeamPlan = built.getOrPut(players) {
        val s = stored[players] ?: error("no split for $players players on $stage")
        TeamPlanner(s.plan).split(s.lanes)
    }
}

/**
 * The team splits for every stage, worked out ahead of time and bundled next to the
 * stage files, so the guide only reads them.
 *
 * [generate] is the slow part -- [TeamSolver] on every stage for every team size, every
 * plan split in full -- and runs on a computer, from `./gradlew :core:generateStrategySplits`.
 * The file it writes is line-based like the stage files, and names every press by its
 * clockwise number, so a change to the planner shows up as a readable diff of which
 * players' presses moved.
 *
 * Each stage's entry carries a fingerprint of the stage it was worked out for. Reading
 * the file checks every one against the stage file, so a corrected stage whose splits
 * were not regenerated fails the tests rather than showing splits of the old one.
 */
object StrategySplits {
    /** Team sizes worked out: the guide asks for two to five, and draws one before it asks. */
    val TEAM_SIZES = 1..5

    fun fileName(room: StrategyRoom): String = "${room.id}-splits.txt"

    /** Every stage of [room] with its splits, in level then stage order. */
    fun bundled(room: StrategyRoom): List<StageSplits> {
        val resource = "/strategy/${fileName(room)}"
        val stream = StrategySplits::class.java.getResourceAsStream(resource)
            ?: error("missing resource $resource")
        return parse(StrategyStages.bundled(room), stream.bufferedReader().use { it.readText() })
    }

    /** [bundled] grouped by level number. */
    fun levels(room: StrategyRoom): Map<Int, List<StageSplits>> = bundled(room).groupBy { it.stage.level }.toSortedMap()

    /** Works out every split for [stages]. Minutes, not milliseconds. */
    fun generate(stages: List<StrategyStage>): List<StageSplits> =
        stages.parallelStream().map { stage ->
            val solver = TeamSolver(StrategySolver(stage).solve() ?: error("no plan for $stage"))
            val stored = TEAM_SIZES.associateWith { players ->
                val team = solver.schedule(players)
                StageSplits.Stored(team.plan, team.lanes.map { it.shots })
            }
            StageSplits(stage, stored)
        }.collect(java.util.stream.Collectors.toList())

    /** A stable digest of everything about a stage that its plans depend on. */
    fun fingerprint(stage: StrategyStage): String {
        fun cells(c: Collection<Cell>) = c.sortedWith(compareBy({ it.y }, { it.x })).joinToString(";") { "${it.x},${it.y}" }
        val text = buildString {
            append("${stage.level} ${stage.index} ${stage.stageCount} ${stage.width}x${stage.height}\n")
            append("panels ${stage.panels.joinToString { "${it.first}-${it.last}" }} gap ${stage.gap?.let { "${it.first}-${it.last}" }}\n")
            append("rules ${stage.gunsBlock} ${stage.redRule}\n")
            append("guns ${stage.guns.joinToString(";") { "${it.x},${it.y},${it.direction.letter}" }}\n")
            append("targets ${cells(stage.targets)}\n")
            append("mirrors ${cells(stage.mirrors)}\n")
            stage.reds?.let { r -> append("reds ${r.periodMillis} ${r.frames.joinToString("|") { cells(it) }}\n") }
            stage.movingTargets?.let { m ->
                append("moving ${m.periodMillis} ")
                append(m.frames.joinToString("|") { f -> f.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value.x},${it.value.y}" } })
                append("\n")
            }
        }
        val crc = CRC32()
        crc.update(text.toByteArray(Charsets.UTF_8))
        return "%08x".format(Locale.ROOT, crc.value)
    }

    private val HIT_LETTERS = mapOf(
        Hit.TARGET to 'T', Hit.MIRROR to 'M', Hit.RED to 'R', Hit.GUN to 'G', Hit.WALL to 'W', Hit.MISS to 'X',
    )

    /** The file for [room]'s [splits]: what [parse] reads back. */
    fun write(room: StrategyRoom, splits: List<StageSplits>): String = buildString {
        append("# Team splits for every ${room.displayName} stage and team size, worked out ahead of\n")
        append("# time by TeamSolver so the phone only reads them. Generated -- do not edit. After\n")
        append("# changing ${room.id}.txt or the planner, run ./gradlew :core:generateStrategySplits;\n")
        append("# ./gradlew :core:checkStrategySplits says whether that is needed.\n")
        append("#\n")
        append("# shot <number> <what it hits: Target, Mirror, Red, Gun, Wall, miss(X)> <cell> <frames>\n")
        append("# lane <numbers pressed, in order>, one per player, player 1 first\n")
        for (entry in splits) {
            val stage = entry.stage
            append("\nstage level=${stage.level} index=${stage.index} fingerprint=${fingerprint(stage)}\n")
            val plans = ArrayList<StrategyPlan>()
            for (players in entry.stored.keys.sorted()) {
                val s = entry.stored.getValue(players)
                var number = plans.indexOfFirst { it === s.plan } + 1
                if (number == 0) {
                    plans += s.plan
                    number = plans.size
                    append("plan $number\n")
                    for (shot in s.plan.shots) {
                        val hit = HIT_LETTERS.getValue(shot.hit) + if (shot.hitIndex >= 0) "${shot.hitIndex}" else ""
                        val cell = shot.cell?.let { "${it.x},${it.y}" } ?: "-"
                        append("shot ${shot.label} $hit $cell ${shot.frames.joinToString(",")}\n")
                    }
                }
                val team = entry.forPlayers(players)
                append("split players=$players plan=$number")
                append(String.format(Locale.ROOT, " time=%.1f walk=%.1f\n", team.makespan, team.travel))
                for (lane in s.lanes) {
                    append("lane")
                    for (shot in lane) append(" ").append(s.plan.shots[shot].label)
                    append("\n")
                }
            }
        }
    }

    /**
     * Reads a splits file against the stages it was written for. Fails if a stage has
     * no entry, or has changed since its entry was worked out.
     */
    fun parse(stages: List<StrategyStage>, text: String): List<StageSplits> {
        val byKey = stages.associateBy { it.level to it.index }
        val out = LinkedHashMap<StrategyStage, StageSplits>()
        var stage: StrategyStage? = null
        var plans = HashMap<Int, StrategyPlan>()
        var stored = LinkedHashMap<Int, StageSplits.Stored>()
        var shots: ArrayList<PlannedShot>? = null
        var planNumber = 0
        var lanes: ArrayList<List<Int>>? = null
        var lanesFor = 0
        var lanesPlan: StrategyPlan? = null

        fun closePlan() {
            val s = stage ?: return
            val list = shots ?: return
            plans[planNumber] = StrategyPlan(s, list, TeamPlanner.groupRuns(list.map { it.frames }, s.frameCount))
            shots = null
        }
        fun closeSplit() {
            val list = lanes ?: return
            stored[lanesFor] = StageSplits.Stored(lanesPlan!!, list)
            lanes = null
        }
        fun closeStage() {
            closePlan()
            closeSplit()
            val s = stage ?: return
            out[s] = StageSplits(s, stored)
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val tok = line.split(" ")
            val kv = tok.drop(1).filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            when (tok[0]) {
                "stage" -> {
                    closeStage()
                    val key = kv.getValue("level").toInt() to kv.getValue("index").toInt()
                    val s = byKey[key] ?: error("splits for a stage that does not exist: level ${key.first} stage ${key.second}")
                    check(kv["fingerprint"] == fingerprint(s)) {
                        "$s has changed since its splits were worked out: run ./gradlew :core:generateStrategySplits"
                    }
                    stage = s
                    plans = HashMap()
                    stored = LinkedHashMap()
                }
                "plan" -> {
                    closePlan()
                    closeSplit()
                    planNumber = tok[1].toInt()
                    shots = ArrayList()
                }
                "shot" -> {
                    val s = stage!!
                    val (label, hit, cell, frames) = tok.drop(1)
                    val gun = s.labels.indexOf(label)
                    check(gun >= 0) { "no gun $label on $s" }
                    shots!! += PlannedShot(
                        gun = gun,
                        label = label,
                        hit = HIT_LETTERS.entries.first { it.value == hit[0] }.key,
                        hitIndex = if (hit.length > 1) hit.substring(1).toInt() else -1,
                        cell = if (cell == "-") null else cell.split(",").let { (x, y) -> Cell(x.toInt(), y.toInt()) },
                        frames = frames.split(",").map { it.toInt() },
                    )
                }
                "split" -> {
                    closePlan()
                    closeSplit()
                    lanesFor = kv.getValue("players").toInt()
                    lanesPlan = plans[kv.getValue("plan").toInt()] ?: error("split before its plan on $stage")
                    lanes = ArrayList()
                }
                "lane" -> {
                    val plan = lanesPlan!!
                    lanes!! += tok.drop(1).map { label ->
                        plan.shots.indexOfFirst { it.label == label }.also { check(it >= 0) { "$label is not in the plan for $stage" } }
                    }
                }
                else -> error("unknown line in splits file: $line")
            }
        }
        closeStage()
        return stages.map { s -> out[s] ?: error("no splits for $s: run ./gradlew :core:generateStrategySplits") }
    }
}
