package io.github.manypichr.khmeramountspeech

/** Spoken language of an audio segment. [code] is the folder name inside the audio assets. */
public enum class SpeechLanguage(public val code: String) {
    KHMER("km"),
    ENGLISH("en"),
}

/** Kind of reusable audio segment. [folder] is the folder name inside the audio assets. */
public enum class AudioTokenType(public val folder: String, internal val key: String) {
    NUMBER("numbers", "number"),
    UNIT("units", "unit"),

    /** Optional pre-recorded round amount (មួយពាន់, "two hundred thousand", …). Falls back to `parts`. */
    COMPOUND("compounds", "compound"),
    CURRENCY("currency", "currency"),
    CONNECTOR("connectors", "connector"),

    /** Fixed sentence part placed around the amount (see [VoiceType]). */
    PHRASE("phrases", "phrase"),
}

/** One reusable audio segment in a spoken sequence. */
public data class AudioToken(
    val language: SpeechLanguage,
    val type: AudioTokenType,
    /** Asset value, e.g. "1", "20", "hundred-thousand", "riel", "confirm-prefix". */
    val value: String,
    /** Text spoken by this segment. */
    val text: String,
    /** Compound tokens only: the separate segments to play if the compound audio is unavailable. */
    val parts: List<AudioToken>? = null,
) {
    /** Globally unique id, e.g. "km:compound:1000". */
    val id: String get() = AudioCatalog.assetId(type, value, language)

    /** Path of the clip inside the audio root, without extension: "audio/km/numbers/1". */
    val resourcePath: String get() = "audio/${language.code}/${type.folder}/$value"

    override fun toString(): String = id
}

/** Catalog entry for one audio file. */
public data class AudioAsset(
    val language: SpeechLanguage,
    val type: AudioTokenType,
    val value: String,
    val text: String,
    /** Romanization to help non-Khmer readers review the asset list. */
    val romanized: String,
    /** Common assets are decoded ahead of time by [KhmerAmountSpeaker.preload]. */
    val preload: Boolean,
) {
    val id: String get() = AudioCatalog.assetId(type, value, language)
    val token: AudioToken get() = AudioToken(language, type, value, text)
}

/**
 * Catalog of every reusable audio segment, per language — the single source of truth for the
 * number formatters and the player. Mirrors the iOS library and the web project, so the same
 * recordings work on every platform.
 */
public object AudioCatalog {
    private class Seed(val value: String, val text: String, val romanized: String, val preload: Boolean = true)
    private class PartRef(val type: AudioTokenType, val value: String)
    private class CompoundSeed(val value: String, val parts: List<PartRef>)

    // Khmer

    private val kmNumbers = listOf(
        Seed("0", "សូន្យ", "soun", preload = false),
        Seed("1", "មួយ", "muoy"),
        Seed("2", "ពីរ", "pir"),
        Seed("3", "បី", "bei"),
        Seed("4", "បួន", "buon"),
        Seed("5", "ប្រាំ", "pram"),
        Seed("6", "ប្រាំមួយ", "pram muoy"),
        Seed("7", "ប្រាំពីរ", "pram pir"),
        Seed("8", "ប្រាំបី", "pram bei"),
        Seed("9", "ប្រាំបួន", "pram buon"),
        Seed("10", "ដប់", "dap"),
        Seed("20", "ម្ភៃ", "mphey"),
        Seed("30", "សាមសិប", "samseb"),
        Seed("40", "សែសិប", "saeseb"),
        Seed("50", "ហាសិប", "haseb"),
        Seed("60", "ហុកសិប", "hokseb"),
        Seed("70", "ចិតសិប", "chetseb"),
        Seed("80", "ប៉ែតសិប", "paetseb"),
        Seed("90", "កៅសិប", "kauseb"),
    )

    private val kmUnits = listOf(
        Seed("hundred", "រយ", "roy"),
        Seed("thousand", "ពាន់", "poan"),
        Seed("ten-thousand", "ម៉ឺន", "meun"),
        Seed("hundred-thousand", "សែន", "saen"),
        Seed("million", "លាន", "lean"),
    )

    /** "<digit> <unit>" for each unit, e.g. 20000 → ពីរ + ម៉ឺន. */
    private val kmCompounds: List<CompoundSeed> = listOf(
        Triple("hundred", 100L, 9), Triple("thousand", 1_000L, 9), Triple("ten-thousand", 10_000L, 9),
        Triple("hundred-thousand", 100_000L, 9), Triple("million", 1_000_000L, 1),
    ).flatMap { (unit, multiplier, maxDigit) ->
        (1..maxDigit).map { d ->
            CompoundSeed((d * multiplier).toString(), listOf(PartRef(AudioTokenType.NUMBER, "$d"), PartRef(AudioTokenType.UNIT, unit)))
        }
    }

    private val kmCurrency = listOf(
        Seed("riel", "រៀល", "riel"),
        Seed("dollar", "ដុល្លារ", "dollar"),
        Seed("cent", "សេន", "sen"),
    )

    private val kmConnectors = listOf(Seed("and", "និង", "nueng"))

    private val kmPhrases = listOf(
        Seed("confirm-prefix", "ទឹកប្រាក់ចំនួន", "tuek prak chomnuon"),
        Seed("confirm-suffix", "សូមផ្ទៀងផ្ទាត់", "som phtieng phtat"),
        Seed("received-prefix", "ទទួលប្រាក់ចំនួន", "totuol prak chomnuon"),
    )

    // English

    private val enWords = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val enTens = listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    private val enNumbers: List<Seed> =
        enWords.mapIndexed { i, word -> Seed("$i", word, word, preload = i != 0) } +
            enTens.mapIndexed { i, word -> Seed("${(i + 2) * 10}", word, word) }

    private val enUnits = listOf(
        Seed("hundred", "hundred", "hundred"),
        Seed("thousand", "thousand", "thousand"),
        Seed("million", "million", "million"),
        Seed("billion", "billion", "billion", preload = false),
    )

    /** Same values as the Khmer compounds: one hundred … nine hundred thousand, one million. */
    private val enCompounds: List<CompoundSeed> = buildList {
        fun number(v: Int) = PartRef(AudioTokenType.NUMBER, "$v")
        val hundred = PartRef(AudioTokenType.UNIT, "hundred")
        val thousand = PartRef(AudioTokenType.UNIT, "thousand")
        for (d in 1..9) add(CompoundSeed("${d * 100}", listOf(number(d), hundred)))
        for (d in 1..9) add(CompoundSeed("${d * 1_000}", listOf(number(d), thousand)))
        for (d in 1..9) add(CompoundSeed("${d * 10_000}", listOf(number(d * 10), thousand)))
        for (d in 1..9) add(CompoundSeed("${d * 100_000}", listOf(number(d), hundred, thousand)))
        add(CompoundSeed("1000000", listOf(number(1), PartRef(AudioTokenType.UNIT, "million"))))
    }

    private val enCurrency = listOf(
        Seed("riel", "riel", "riel"),
        Seed("dollar", "dollar", "dollar"),
        Seed("dollars", "dollars", "dollars"),
        Seed("cent", "cent", "cent"),
        Seed("cents", "cents", "cents"),
    )

    private val enPhrases = listOf(
        Seed("confirm-prefix", "The amount is", "the amount is"),
        Seed("confirm-suffix", "please verify", "please verify"),
        Seed("received-prefix", "Received", "received"),
    )

    // Catalog

    /** Every asset, Khmer first. */
    public val assets: List<AudioAsset> =
        buildLanguage(SpeechLanguage.KHMER, kmNumbers, kmUnits, kmCompounds, kmCurrency, kmConnectors, kmPhrases) +
            buildLanguage(SpeechLanguage.ENGLISH, enNumbers, enUnits, enCompounds, enCurrency, emptyList(), enPhrases)

    private val assetsById: Map<String, AudioAsset> = assets.associateBy { it.id }

    /** "km|number:2|unit:thousand" → "2000" */
    private val compoundByParts: Map<String, String> = buildMap {
        for (c in kmCompounds) put(partsKey(SpeechLanguage.KHMER, c.parts.map { it.type to it.value }), c.value)
        for (c in enCompounds) put(partsKey(SpeechLanguage.ENGLISH, c.parts.map { it.type to it.value }), c.value)
    }

    public fun assetId(type: AudioTokenType, value: String, language: SpeechLanguage): String =
        "${language.code}:${type.key}:$value"

    public fun asset(type: AudioTokenType, value: String, language: SpeechLanguage): AudioAsset? =
        assetsById[assetId(type, value, language)]

    public fun asset(id: String): AudioAsset? = assetsById[id]

    /** Token for a catalog asset. Throws on unknown assets, which indicates a programming error. */
    public fun token(type: AudioTokenType, value: String, language: SpeechLanguage): AudioToken =
        checkNotNull(asset(type, value, language)) { "Unknown audio asset ${assetId(type, value, language)}" }.token

    /** The pre-recorded compound that says exactly [parts] (e.g. [ពីរ, ពាន់] → ពីរពាន់), if any. */
    public fun compound(parts: List<AudioToken>): AudioToken? {
        if (parts.size < 2) return null
        val language = parts.first().language
        if (!parts.all { it.language == language && it.type != AudioTokenType.COMPOUND }) return null
        val value = compoundByParts[partsKey(language, parts.map { it.type to it.value })] ?: return null
        val base = token(AudioTokenType.COMPOUND, value, language)
        return AudioToken(language, AudioTokenType.COMPOUND, value, base.text, parts)
    }

    /**
     * Joins segment texts the way each language is written: Khmer without spaces, English with
     * spaces and hyphenated tens ("twenty-five").
     */
    public fun joinText(tokens: List<AudioToken>, language: SpeechLanguage): String =
        joinTextItems(tokens.map { Triple(it.type, it.value, it.text) }, language)

    private fun joinTextItems(items: List<Triple<AudioTokenType, String, String>>, language: SpeechLanguage): String {
        if (language == SpeechLanguage.KHMER) return items.joinToString("") { it.third }
        val out = StringBuilder()
        items.forEachIndexed { i, (type, value, text) ->
            if (i > 0) {
                val (prevType, prevValue) = items[i - 1]
                val hyphen = prevType == AudioTokenType.NUMBER && (prevValue.toIntOrNull() ?: 0) >= 20 &&
                    type == AudioTokenType.NUMBER && (value.toIntOrNull() ?: 99) < 10
                out.append(if (hyphen) "-" else " ")
            }
            out.append(text)
        }
        return out.toString()
    }

    private fun partsKey(language: SpeechLanguage, parts: List<Pair<AudioTokenType, String>>): String =
        (listOf(language.code) + parts.map { (type, value) -> "${type.key}:$value" }).joinToString("|")

    private fun buildLanguage(
        language: SpeechLanguage,
        numbers: List<Seed>,
        units: List<Seed>,
        compounds: List<CompoundSeed>,
        currency: List<Seed>,
        connectors: List<Seed>,
        phrases: List<Seed>,
    ): List<AudioAsset> {
        fun build(type: AudioTokenType, seeds: List<Seed>) =
            seeds.map { AudioAsset(language, type, it.value, it.text, it.romanized, it.preload) }

        val words = build(AudioTokenType.NUMBER, numbers) + build(AudioTokenType.UNIT, units)
        val compoundSeeds = compounds.map { compound ->
            val found = compound.parts.map { ref -> words.first { it.type == ref.type && it.value == ref.value } }
            val text = joinTextItems(found.map { Triple(it.type, it.value, it.text) }, language)
            Seed(compound.value, text, found.joinToString(" ") { it.romanized })
        }
        return words + build(AudioTokenType.COMPOUND, compoundSeeds) + build(AudioTokenType.CURRENCY, currency) +
            build(AudioTokenType.CONNECTOR, connectors) + build(AudioTokenType.PHRASE, phrases)
    }
}
