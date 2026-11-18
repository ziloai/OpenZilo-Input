package com.openzilo.input

private val filler = Regex("(?i)(^|[\\s，,。.！!？?；;：:…])(?:嗯+|呃+|额+|唔+|um+|uh+|erm?)(?=$|[\\s，,。.！!？?；;：:…])")

internal fun polishTranscript(input: String): String {
    var text = input.replace(Regex("[\\s\\u00A0]+"), " ").trim()
    if (text.isEmpty()) return ""
    repeat(4) {
        val reduced = text.replace(filler, "$1")
        if (reduced != text) text = reduced
    }
    text = text.replace(Regex("\\s+([，,。.!！?？；;：:、])"), "$1")
    text = text.replace(Regex("([，,、；;：:])\\1+"), "$1")
    text = text.replace(Regex("([。.!！?？])\\1+"), "$1")
    text = text.replace(Regex("([，、；：。！？])\\s+"), "$1")
    text = text.replace(Regex("^[，,、；;：:]+\\s*"), "").trim()
    if (text.isEmpty() || text.none { it.isLetterOrDigit() }) return ""
    text = capitalizeSentences(text)
    if (text.last().isLetterOrDigit() || text.last() in "）】」’”)]}\"") {
        text += if (text.any { it.code in 0x3400..0x9FFF || it.code in 0xF900..0xFAFF }) "。" else "."
    }
    return text
}

private fun capitalizeSentences(value: String): String {
    val characters = value.toCharArray()
    var sentenceStart = true
    for (index in characters.indices) {
        val character = characters[index]
        if (character.isWhitespace()) continue
        if (sentenceStart) {
            if (character.isLetter()) {
                characters[index] = character.uppercaseChar()
                sentenceStart = false
            } else if (!character.isDigit()) {
                sentenceStart = false
            }
        }
        if (character in ".!?。！？\n") sentenceStart = true
    }
    return String(characters)
}
