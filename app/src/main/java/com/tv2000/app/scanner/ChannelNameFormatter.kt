package com.tv2000.app.scanner

/**
 * Converts a release-style directory name into the short name shown as a channel.
 *
 * This function must never be used as a storage key: the original directory name is still used
 * for paths, stable IDs and playback history.
 */
internal fun displayChannelName(rawName: String): String {
    val original = rawName.trim()
    if (original.isEmpty()) return original

    val withoutMetadataBlocks = removeMetadataBlocks(original)
    val unwrappedTitle = if (withoutMetadataBlocks != original) {
        unwrapSingleBracketedTitle(withoutMetadataBlocks)
    } else {
        original
    }
    val withoutTechnicalSuffix = removeTechnicalSuffix(unwrappedTitle)
    val withoutEnglishAlias = preferLeadingChineseTitle(withoutTechnicalSuffix)
    val cleaned = cleanEdges(withoutEnglishAlias)

    return cleaned.ifEmpty { original }
}

private fun removeMetadataBlocks(name: String): String {
    val matches = BRACKET_BLOCK.findAll(name).toList()
    if (matches.isEmpty()) return name

    val stripped = BRACKET_BLOCK.replace(name) { match ->
        val content = match.groupValues[1].trim()
        if (isMetadataBlock(content, matches.size)) " " else match.value
    }

    return stripped.takeIf { it.isNotBlank() } ?: name
}

private fun isMetadataBlock(content: String, blockCount: Int): Boolean {
    if (METADATA_FRAGMENT.containsMatchIn(content)) return true
    if (RELEASE_GROUP_BLOCK.matches(content)) return true

    val containsYear = YEAR.containsMatchIn(content)
    return containsYear && (blockCount > 1 || MEDIA_KIND.containsMatchIn(content))
}

private fun unwrapSingleBracketedTitle(name: String): String {
    val trimmed = name.trim()
    val match = SINGLE_BRACKETED_TITLE.matchEntire(trimmed) ?: return trimmed
    return match.groupValues[1].trim()
}

private fun removeTechnicalSuffix(name: String): String {
    val tokens = NAME_TOKEN.findAll(name).toList()
    val technicalTokenIndex = tokens.indexOfFirst { match ->
        isTechnicalToken(match.value)
    }
    if (technicalTokenIndex < 0) return name

    val boundaryTokenIndex = if (
        technicalTokenIndex > 0 && YEAR.matches(tokens[technicalTokenIndex - 1].value)
    ) {
        technicalTokenIndex - 1
    } else {
        technicalTokenIndex
    }

    return name.substring(0, tokens[boundaryTokenIndex].range.first)
}

private fun isTechnicalToken(token: String): Boolean =
    SEASON_TOKEN.matches(token) ||
        EPISODE_COUNT_TOKEN.matches(token) ||
        LANGUAGE_TOKEN.matches(token) ||
        SUBTITLE_TOKEN.matches(token) ||
        RESOLUTION_TOKEN.matches(token) ||
        SOURCE_TOKEN.matches(token) ||
        VIDEO_TOKEN.matches(token) ||
        AUDIO_TOKEN.matches(token) ||
        QUALITY_TOKEN.matches(token)

private fun preferLeadingChineseTitle(name: String): String {
    val parts = name.split('.')
        .map(String::trim)
        .filter(String::isNotEmpty)
    if (parts.size < 2 || !HAN_CHARACTER.containsMatchIn(parts.first())) return name

    val alias = parts.drop(1)
    if (alias.size == 1 && ROMAN_NUMERAL.matches(alias.single())) {
        return "${parts.first()} ${alias.single().uppercase()}"
    }

    return if (alias.all(ENGLISH_TITLE_PART::matches)) parts.first() else name
}

private fun cleanEdges(name: String): String = name
    .trim()
    .trim(' ', '.', '_', '-')
    .replace(WHITESPACE, " ")

private val BRACKET_BLOCK = Regex("""\[([^\[\]]+)]""")
private val SINGLE_BRACKETED_TITLE = Regex("""^\[([^\[\]]+)]$""")
private val YEAR = Regex("""(?:19|20)\d{2}""")
private val MEDIA_KIND = Regex("""(?i)\b(?:movie|film|tv|anime|ova)\b""")
private val RELEASE_GROUP_BLOCK = Regex(
    """(?i)(?:[\w-]+-(?:studio|raws?)|(?:studio|fansub|raws?)[\w-]*)""",
)
private val METADATA_FRAGMENT = Regex(
    """(?i)(?:\d{3,4}p|4k|8k|web[ ._-]?dl|webrip|blu[ ._-]?ray|b[dr]rip|remux|hdtv|""" +
        """uhd|dvd[ ._-]?rip|h\.?26[45]|x26[45]|hevc|avc|av1|10[ ._-]?bit|hdr10?|sdr|""" +
        """dolby[ ._-]?vision|aac|dts|truehd|atmos|flac|ma10p)""",
)
private val NAME_TOKEN = Regex("""[^\s._]+""")
private val SEASON_TOKEN = Regex("""(?i)s\d{1,3}(?:e\d{1,3}(?:[-e]\d{1,3})?)?""")
private val EPISODE_COUNT_TOKEN = Regex("""(?:全)?\d{1,4}(?:集|话|話)(?:全)?""")
private val LANGUAGE_TOKEN = Regex(
    """(?i)(?:国语|國語|粤语|粵語|普通话|普通話|国粤双语|國粵雙語|""" +
        """国语粤语|國語粵語|mandarin|cantonese)""",
)
private val SUBTITLE_TOKEN = Regex(
    """(?:简体中字|簡體中字|繁体中字|繁體中字|简繁中字|簡繁中字|""" +
        """中英字幕|中字|简中|簡中|繁中|无字幕|無字幕)""",
)
private val RESOLUTION_TOKEN = Regex("""(?i)(?:\d{3,4}p|4k|8k)""")
private val SOURCE_TOKEN = Regex(
    """(?i)(?:web-?dl|webrip|bluray|b[dr]rip|remux|hdtv|uhd|dvd-?rip)""",
)
private val VIDEO_TOKEN = Regex("""(?i)(?:h\.?26[45]|x26[45]|hevc|avc|av1|mpeg[24]?)""")
private val AUDIO_TOKEN = Regex(
    """(?i)(?:aac(?:-.+)?|dts(?:-.+)?|ddp?|eac3|ac3|truehd|atmos|flac)""",
)
private val QUALITY_TOKEN = Regex(
    """(?i)(?:8|10|12)bit|hdr10?|sdr|dolby-?vision|dv|hi10p|ma10p""",
)
private val HAN_CHARACTER = Regex("""[\u3400-\u9fff]""")
private val ROMAN_NUMERAL = Regex("""(?i)(?:i|ii|iii|iv|v|vi|vii|viii|ix|x)""")
private val ENGLISH_TITLE_PART = Regex("""[A-Za-z][A-Za-z0-9'&+!-]*""")
private val WHITESPACE = Regex("""\s+""")
