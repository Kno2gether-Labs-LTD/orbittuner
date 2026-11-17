package dev.avijit.orbittuner.backing

enum class DrumKind { Kick, Snare, Rim, Hat, Brush, Shaker, Sticks }

/** How the plucked strings ring in a groove: [sustainS] is the fundamental's 60 dB decay time, [brightness] 0..1. */
data class StringStyle(val sustainS: Double, val brightness: Double)

/**
 * A groove is one bar of patterns on a step grid ([stepsPerBeat] steps per beat, [beats] beats). Pattern letters:
 * - drums: `X` accent, `x` normal, `o` ghost, `.` rest;
 * - bass: `R` root, `r` soft root, `F` fifth, `f` soft fifth, `O` octave, `L` fifth below; each note lasts until the
 *   next one (or the bar line) times [bassLength];
 * - strum: `D`/`d` down (loud/soft), `U`/`u` up.
 * [swing] is where the second step of each pair lands, as a fraction of the pair (0.5 straight, 2/3 triplet swing).
 * [fill] replaces drum lines in the last bar of the progression (when it has 4+ bars), to mark the turn-around.
 * [Drone] plucks a tanpura cycle (Pa, Sa, Sa, low Sa) instead of strumming chords.
 */
enum class Groove(
    val label: String,
    val beats: Int,
    val stepsPerBeat: Int,
    val swing: Double,
    val drums: Map<DrumKind, String>,
    val bass: String,
    val strum: String,
    val style: StringStyle,
    val bassLength: Double = 0.92,
    val fill: Map<DrumKind, String> = emptyMap(),
) {
    Rock8(
        "Rock 8ths", 4, 2, 0.5,
        mapOf(DrumKind.Kick to "X...X.x.", DrumKind.Snare to "..X...X.", DrumKind.Hat to "xoxoxoxo"),
        bass = "R.rRR.rF", strum = "DdDdDdDd", style = StringStyle(1.8, 0.55),
        fill = mapOf(DrumKind.Kick to "X...X...", DrumKind.Snare to "..X.xoXX", DrumKind.Hat to "xoxoxo.."),
    ),
    Pop(
        "Pop", 4, 2, 0.5,
        mapOf(DrumKind.Kick to "X..xX.x.", DrumKind.Snare to "..X...X.", DrumKind.Hat to "xoxoxoxo"),
        bass = "R..rR.F.", strum = "D.DU.UDU", style = StringStyle(2.0, 0.5),
        fill = mapOf(DrumKind.Kick to "X..xX...", DrumKind.Snare to "..X..xXx", DrumKind.Hat to "xoxoxo.."),
    ),
    Ballad(
        "Pop ballad", 4, 2, 0.5,
        mapOf(DrumKind.Kick to "X.....x.", DrumKind.Rim to "..x...x.", DrumKind.Hat to "oooooooo"),
        bass = "R.....F.", strum = "D...D.DU", style = StringStyle(2.8, 0.42),
        fill = mapOf(DrumKind.Kick to "X.....x.", DrumKind.Rim to "..x...xo", DrumKind.Hat to "oooooo.."),
    ),
    Shuffle(
        "Shuffle", 4, 2, 2.0 / 3,
        mapOf(DrumKind.Kick to "X...X..x", DrumKind.Snare to "..X...X.", DrumKind.Hat to "xoxoxoxo"),
        bass = "R.F.O.Fr", strum = "D.DU.UDU", style = StringStyle(1.5, 0.5),
    ),
    SixEight(
        "6/8", 6, 1, 0.5,
        mapOf(DrumKind.Kick to "X..x..", DrumKind.Rim to ".o.X.o", DrumKind.Shaker to "xoxxox"),
        bass = "R..F..", strum = "D.UD.U", style = StringStyle(2.2, 0.5),
    ),
    SevenEight(
        "7/8", 7, 1, 0.5,
        mapOf(DrumKind.Kick to "X.x....", DrumKind.Snare to "....X..", DrumKind.Hat to "xoxoxoo"),
        bass = "R.r.F..", strum = "D.d.D.u", style = StringStyle(1.6, 0.55),
    ),
    Funk(
        "Funk 16ths", 4, 4, 0.54,
        mapOf(
            DrumKind.Kick to "X.....x...X..x..",
            DrumKind.Snare to "....X..o.o..X..o",
            DrumKind.Hat to "xoxoxoxoxoxoxoxo",
        ),
        bass = "R..r..O...R.F..r", strum = "D.duD.dU.uD.duDU", style = StringStyle(0.35, 0.7), bassLength = 0.55,
    ),
    Swing(
        "Jazz swing", 4, 2, 2.0 / 3,
        mapOf(DrumKind.Brush to "x.Xox.Xo", DrumKind.Kick to "o...o...", DrumKind.Hat to "..x...x."),
        bass = "R.F.O.F.", strum = "d.D.d.D.", style = StringStyle(0.9, 0.35), bassLength = 0.85,
    ),
    Drone(
        "Drone", 4, 1, 0.5, emptyMap(),
        bass = "....", strum = "DDDD", style = StringStyle(6.0, 0.62),
    ),
}
