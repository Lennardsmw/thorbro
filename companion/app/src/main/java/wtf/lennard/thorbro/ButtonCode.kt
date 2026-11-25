package wtf.lennard.thorbro

/** button codes (8 presses of ↑↓←→ABXY), used to become friends ([Pals.showCode] / [Pals.redeemCode]) */
object ButtonCode {
    const val LEN = 8
    /** what the code is made of; as digits 1–8 in this order (for keyboards and the web panel) */
    val SYMBOLS = listOf("↑", "↓", "←", "→", "A", "B", "X", "Y")

    /** "47120563" (digits 0–7) → "A ↓ ↑ ← …" */
    fun codeText(c: String) = c.map { SYMBOLS[it - '0'] }.joinToString(" ")
    fun codeDigits(c: String) = c.map { (it - '0' + 1).toString() }.joinToString("")

    /** whatever was typed — digits 1–8, letters U D L R A B X Y, or arrows — as the internal form, or null */
    fun normalize(input: String): String? {
        val out = StringBuilder()
        for (ch in input.uppercase()) {
            val i = when (ch) {
                in '1'..'8' -> ch - '1'
                '↑', 'U' -> 0; '↓', 'D' -> 1; '←', 'L' -> 2; '→', 'R' -> 3
                'A' -> 4; 'B' -> 5; 'X' -> 6; 'Y' -> 7
                else -> -1
            }
            if (i >= 0) out.append(i)
        }
        return out.toString().takeIf { it.length == LEN }
    }
}
