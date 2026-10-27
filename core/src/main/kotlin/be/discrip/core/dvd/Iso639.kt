package be.discrip.core.dvd

/**
 * DVDs name languages with ISO 639-1 (two letters); Matroska's Language element wants
 * ISO 639-2, and for the twenty languages that have two forms, the bibliographic one ("dut", not
 * "nld"), as MKVToolNix writes them.
 */
object Iso639 {

    /** The ISO 639-2/B code for an ISO 639-1 code (any case), or null when unknown. */
    fun toBibliographic(code: String?): String? = code?.trim()?.lowercase()?.let { TABLE[it] }

    /** The ISO 639-1 code for an ISO 639-2/B code as Matroska stores it, or null when unknown. */
    fun fromBibliographic(code: String?): String? = code?.trim()?.lowercase()?.let { REVERSE[it] }

    /** Codes withdrawn from ISO 639-1 that older discs can still carry. */
    private val WITHDRAWN = mapOf(
        "bh" to "bih",
        "iw" to "heb",
        "in" to "ind",
        "ji" to "yid",
        "jw" to "jav",
        "mo" to "rum",
    )

    private val TABLE: Map<String, String> = WITHDRAWN + mapOf(
        "aa" to "aar", "ab" to "abk", "ae" to "ave", "af" to "afr", "ak" to "aka", "am" to "amh",
        "an" to "arg", "ar" to "ara", "as" to "asm", "av" to "ava", "ay" to "aym", "az" to "aze",
        "ba" to "bak", "be" to "bel", "bg" to "bul", "bi" to "bis", "bm" to "bam",
        "bn" to "ben", "bo" to "tib", "br" to "bre", "bs" to "bos", "ca" to "cat", "ce" to "che",
        "ch" to "cha", "co" to "cos", "cr" to "cre", "cs" to "cze", "cu" to "chu", "cv" to "chv",
        "cy" to "wel", "da" to "dan", "de" to "ger", "dv" to "div", "dz" to "dzo", "ee" to "ewe",
        "el" to "gre", "en" to "eng", "eo" to "epo", "es" to "spa", "et" to "est", "eu" to "baq",
        "fa" to "per", "ff" to "ful", "fi" to "fin", "fj" to "fij", "fo" to "fao", "fr" to "fre",
        "fy" to "fry", "ga" to "gle", "gd" to "gla", "gl" to "glg", "gn" to "grn", "gu" to "guj",
        "gv" to "glv", "ha" to "hau", "he" to "heb", "hi" to "hin", "ho" to "hmo", "hr" to "hrv",
        "ht" to "hat", "hu" to "hun", "hy" to "arm", "hz" to "her", "ia" to "ina", "id" to "ind",
        "ie" to "ile", "ig" to "ibo", "ii" to "iii", "ik" to "ipk", "io" to "ido", "is" to "ice",
        "it" to "ita", "iu" to "iku", "ja" to "jpn", "jv" to "jav", "ka" to "geo", "kg" to "kon",
        "ki" to "kik", "kj" to "kua", "kk" to "kaz", "kl" to "kal", "km" to "khm", "kn" to "kan",
        "ko" to "kor", "kr" to "kau", "ks" to "kas", "ku" to "kur", "kv" to "kom", "kw" to "cor",
        "ky" to "kir", "la" to "lat", "lb" to "ltz", "lg" to "lug", "li" to "lim", "ln" to "lin",
        "lo" to "lao", "lt" to "lit", "lu" to "lub", "lv" to "lav", "mg" to "mlg", "mh" to "mah",
        "mi" to "mao", "mk" to "mac", "ml" to "mal", "mn" to "mon", "mr" to "mar", "ms" to "may",
        "mt" to "mlt", "my" to "bur", "na" to "nau", "nb" to "nob", "nd" to "nde", "ne" to "nep",
        "ng" to "ndo", "nl" to "dut", "nn" to "nno", "no" to "nor", "nr" to "nbl", "nv" to "nav",
        "ny" to "nya", "oc" to "oci", "oj" to "oji", "om" to "orm", "or" to "ori", "os" to "oss",
        "pa" to "pan", "pi" to "pli", "pl" to "pol", "ps" to "pus", "pt" to "por", "qu" to "que",
        "rm" to "roh", "rn" to "run", "ro" to "rum", "ru" to "rus", "rw" to "kin", "sa" to "san",
        "sc" to "srd", "sd" to "snd", "se" to "sme", "sg" to "sag", "si" to "sin", "sk" to "slo",
        "sl" to "slv", "sm" to "smo", "sn" to "sna", "so" to "som", "sq" to "alb", "sr" to "srp",
        "ss" to "ssw", "st" to "sot", "su" to "sun", "sv" to "swe", "sw" to "swa", "ta" to "tam",
        "te" to "tel", "tg" to "tgk", "th" to "tha", "ti" to "tir", "tk" to "tuk", "tl" to "tgl",
        "tn" to "tsn", "to" to "ton", "tr" to "tur", "ts" to "tso", "tt" to "tat", "tw" to "twi",
        "ty" to "tah", "ug" to "uig", "uk" to "ukr", "ur" to "urd", "uz" to "uzb", "ve" to "ven",
        "vi" to "vie", "vo" to "vol", "wa" to "wln", "wo" to "wol", "xh" to "xho", "yi" to "yid",
        "yo" to "yor", "za" to "zha", "zh" to "chi", "zu" to "zul",
    )

    private val REVERSE: Map<String, String> = TABLE.entries.filter { it.key !in WITHDRAWN }.associate { (two, three) -> three to two }

    /** Number of current ISO 639-1 codes in the table, for the tests. */
    internal val currentCodeCount: Int get() = TABLE.size - WITHDRAWN.size
}
