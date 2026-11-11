//
//  WallClock.kt
//  outbound
//

package earth.outbound

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Helpers for "floating" wall clocks: local times as written (e.g. "4:00 PM" in
 * Tokyo), held as instants whose UTC components are the wall clock. They must only be
 * formatted in UTC, and become a real moment only once read in a zone.
 */
object WallClock {
    /** The time alone, e.g. "4:00 PM", for `text` and the spans' `startText`/`endText`. */
    val hourMinute: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm\u202Fa", Locale.US)

    /** The day and time, e.g. "Wed, Nov 11 at 4:00 PM". */
    val weekdayMonthDayHourMinute: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm\u202Fa", Locale.US)

    /** A day without its year, e.g. "Nov 5". */
    val monthDay: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    /** A day with its year, e.g. "Nov 5, 2026". */
    val monthDayYear: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    /** A floating wall clock as written, e.g. "4:00 PM". */
    fun text(local: Instant, style: DateTimeFormatter): String =
        style.withZone(ZoneOffset.UTC).format(local)

    /** A floating wall clock reinterpreted as a real moment in `zone`. */
    fun instant(local: Instant, zone: ZoneId): Instant =
        LocalDateTime.ofInstant(local, ZoneOffset.UTC).atZone(zone).toInstant()

    /**
     * A short code for a zone at a given moment (handles DST), e.g. "EST"/"EDT".
     *
     * Each platform only knows letter codes for the zones a locale commonly uses, and
     * they don't agree on them, so the codes are kept here (see `zoneCodes`): the ones
     * English speakers know a zone by (EST in the US, AEDT in Australia, IST in India,
     * CET in the UK…), falling back to the GMT offset ("GMT+9") where a zone has none.
     */
    fun zoneAbbreviation(zone: ZoneId, date: Instant): String {
        val offset = zone.rules.getOffset(date).totalSeconds
        zoneCodes[zone.id]?.get(offset)?.let { return it }
        if (offset == 0) return "GMT"
        val minutes = abs(offset) / 60
        val sign = if (offset < 0) "-" else "+"
        return "GMT$sign${minutes / 60}" + if (minutes % 60 == 0) "" else ":%02d".format(Locale.ROOT, minutes % 60)
    }

    /**
     * Letter codes per zone identifier and UTC offset in seconds. Plain letter codes
     * only (no spelled-out names like "Gulf ST"), leaving out ones that would read as
     * another zone: "BST" for Bangladesh collides with British Summer Time.
     */
    private val zoneCodes: Map<String, Map<Int, String>> by lazy {
        val codes = mutableMapOf<String, Map<Int, String>>()
        for (group in zoneCodeTable.split(';')) {
            val parts = group.split('|')
            if (parts.size != 2) continue
            val byOffset = parts[0].trim().split(whitespace).chunked(2)
                .associate { (code, minutes) -> minutes.toInt() * 60 to code }
            parts[1].trim().split(whitespace).forEach { codes[it] = byOffset }
        }
        codes
    }

    private val whitespace = Regex("\\s+")

    /** Parses a naive wall clock like "2026-11-01T11:00:00" (fractional seconds ignored). */
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)

    /**
     * A naive wall clock like "2026-11-01T11:00:00" as a floating UTC instant, or null
     * when it isn't one.
     */
    fun parse(string: String): Instant? =
        try {
            LocalDateTime.parse(string.take(19), formatter).toInstant(ZoneOffset.UTC)
        } catch (_: DateTimeException) {
            null
        }

    /**
     * A floating UTC instant (as decoded by `WallClockSerializer`) back to its naive
     * wall clock, e.g. "2026-11-01T11:00:00", for sending it on unchanged.
     */
    fun naiveString(floating: Instant): String =
        formatter.withZone(ZoneOffset.UTC).format(floating)
}

/**
 * Something that runs from a start to an optional end, each a wall clock with its own
 * zone, as the server sends them: the wall clock (`starts_at`, no offset) and, when
 * there's a zone, the same moment as a true UTC instant (`starts_at_utc`).
 *
 * Display the wall clocks with `startText`/`endText`; order by
 * `startInstant(fallbackZone)`, which prefers the true instant and reads a zoneless
 * wall clock in the given zone.
 */
interface WallClockSpan {
    /** The start wall clock as written (`starts_at`), as a floating UTC instant. */
    val startsAtLocal: Instant

    /** The start moment as a true instant (`starts_at_utc`); null when zoneless. */
    val startsAtUtc: Instant?

    /**
     * The zone the start wall clock is printed in; null when none is attached (missing
     * or unrecognized), in which case no zone is shown.
     */
    val startTimezone: ZoneId?

    /** The end wall clock as written (`ends_at`); null iff there's no end. */
    val endsAtLocal: Instant?

    /** The end moment as a true instant (`ends_at_utc`); null without an end or zone. */
    val endsAtUtc: Instant?

    /** The zone for the end wall clock; null without an end or when zoneless. */
    val endTimezone: ZoneId?
}

/** The start wall clock as written, e.g. "4:00 PM". */
fun WallClockSpan.startText(style: DateTimeFormatter): String =
    WallClock.text(startsAtLocal, style)

/** The end wall clock as written, or null when there's no end. */
fun WallClockSpan.endText(style: DateTimeFormatter): String? =
    endsAtLocal?.let { WallClock.text(it, style) }

/**
 * The true start instant, or when zoneless its wall clock read in `fallbackZone`
 * (the trip day's zone, else the device's).
 */
fun WallClockSpan.startInstant(fallbackZone: ZoneId): Instant =
    startsAtUtc ?: WallClock.instant(startsAtLocal, fallbackZone)

/** Like `startInstant(fallbackZone)` for the end; null when there's no end. */
fun WallClockSpan.endInstant(fallbackZone: ZoneId): Instant? {
    val endsAtLocal = endsAtLocal ?: return null
    return endsAtUtc ?: WallClock.instant(endsAtLocal, fallbackZone)
}

/** A short zone label for the start, e.g. "PDT" or "JST", or null when there's no zone. */
val WallClockSpan.startZoneAbbreviation: String?
    get() = startTimezone?.let { WallClock.zoneAbbreviation(it, startsAtUtc ?: startsAtLocal) }

/** A short zone label for the end, or null when there's no end or zone. */
val WallClockSpan.endZoneAbbreviation: String?
    get() {
        val endsAtLocal = endsAtLocal ?: return null
        val endTimezone = endTimezone ?: return null
        return WallClock.zoneAbbreviation(endTimezone, endsAtUtc ?: endsAtLocal)
    }

/**
 * A naive wall clock like "2026-11-01T11:00:00" as a floating UTC instant. Use it on
 * a property with `@Serializable(with = WallClockSerializer::class)`, so the ISO 8601
 * `InstantSerializer`, which needs a `Z` or offset, never sees it. On a nullable
 * property it's null when the key is missing or null.
 */
object WallClockSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("WallClock", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(WallClock.naiveString(value))
    }

    override fun deserialize(decoder: Decoder): Instant {
        val string = decoder.decodeString()
        return WallClock.parse(string) ?: throw SerializationException("Unrecognized local date-time: $string")
    }
}

/**
 * An IANA zone identifier as a `ZoneId`; null when missing, null or unrecognized. Use
 * it on a nullable property with `@Serializable(with = TimeZoneSerializer::class)`.
 */
object TimeZoneSerializer : KSerializer<ZoneId?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("TimeZone", PrimitiveKind.STRING).nullable

    override fun serialize(encoder: Encoder, value: ZoneId?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value.id)
    }

    override fun deserialize(decoder: Decoder): ZoneId? =
        if (decoder.decodeNotNullMark()) timeZone(decoder.decodeString()) else decoder.decodeNull()
}

/** The zone with an IANA identifier, or null when the device doesn't know it. */
fun timeZone(identifier: String): ZoneId? =
    try {
        ZoneId.of(identifier)
    } catch (_: DateTimeException) {
        null
    }

/**
 * The letter codes behind `WallClock.zoneAbbreviation`: groups of
 * `CODE offset-in-minutes … | zone identifiers ;`, with a code for each offset the
 * zones in the group move between.
 */
private const val zoneCodeTable = """
    ACDT 630 ACST 570 |
        Australia/Adelaide Australia/Broken_Hill Australia/South Australia/Yancowinna ;
    ACST 570 |
        Australia/Darwin Australia/North ;
    ACWST 525 |
        Australia/Eucla ;
    AEDT 660 AEST 600 |
        Antarctica/Macquarie Australia/ACT Australia/Canberra Australia/Currie Australia/Hobart
        Australia/Melbourne Australia/NSW Australia/Sydney Australia/Tasmania Australia/Victoria ;
    AEST 600 |
        Australia/Brisbane Australia/Lindeman Australia/Queensland ;
    AKST -540 AKDT -480 |
        America/Anchorage America/Juneau America/Metlakatla America/Nome America/Sitka America/Yakutat
        US/Alaska ;
    AST -240 |
        America/Anguilla America/Antigua America/Aruba America/Barbados America/Blanc-Sablon
        America/Curacao America/Dominica America/Grenada America/Guadeloupe America/Kralendijk
        America/Lower_Princes America/Marigot America/Martinique America/Montserrat
        America/Port_of_Spain America/Puerto_Rico America/Santo_Domingo America/St_Barthelemy
        America/St_Kitts America/St_Lucia America/St_Thomas America/St_Vincent America/Tortola
        America/Virgin ;
    AST -240 ADT -180 |
        America/Glace_Bay America/Goose_Bay America/Halifax America/Moncton America/Thule
        Atlantic/Bermuda Canada/Atlantic ;
    AWST 480 |
        Antarctica/Casey Australia/Perth Australia/West ;
    BST 60 |
        Europe/London ;
    CAT 120 |
        Africa/Blantyre Africa/Bujumbura Africa/Gaborone Africa/Harare Africa/Juba Africa/Khartoum
        Africa/Kigali Africa/Lubumbashi Africa/Lusaka Africa/Maputo Africa/Windhoek ;
    CET 60 |
        Africa/Algiers Africa/Tunis ;
    CET 60 CEST 120 |
        Africa/Ceuta Arctic/Longyearbyen Atlantic/Jan_Mayen CET Europe/Amsterdam Europe/Andorra
        Europe/Belgrade Europe/Berlin Europe/Bratislava Europe/Brussels Europe/Budapest Europe/Busingen
        Europe/Copenhagen Europe/Gibraltar Europe/Ljubljana Europe/Luxembourg Europe/Madrid Europe/Malta
        Europe/Monaco Europe/Oslo Europe/Paris Europe/Podgorica Europe/Prague Europe/Rome
        Europe/San_Marino Europe/Sarajevo Europe/Skopje Europe/Stockholm Europe/Tirane Europe/Vaduz
        Europe/Vatican Europe/Vienna Europe/Warsaw Europe/Zagreb Europe/Zurich MET Poland ;
    CHADT 825 CHAST 765 |
        NZ-CHAT Pacific/Chatham ;
    CST -360 |
        America/Bahia_Banderas America/Belize America/Chihuahua America/Costa_Rica America/El_Salvador
        America/Guatemala America/Managua America/Merida America/Mexico_City America/Monterrey
        America/Regina America/Swift_Current America/Tegucigalpa Canada/Saskatchewan Mexico/General ;
    CST -360 CDT -300 |
        America/Chicago America/Indiana/Knox America/Indiana/Tell_City America/Knox_IN America/Matamoros
        America/Menominee America/North_Dakota/Beulah America/North_Dakota/Center
        America/North_Dakota/New_Salem America/Ojinaga America/Rainy_River America/Rankin_Inlet
        America/Resolute America/Winnipeg Canada/Central US/Central US/Indiana-Starke ;
    EAT 180 |
        Africa/Addis_Ababa Africa/Asmara Africa/Asmera Africa/Dar_es_Salaam Africa/Djibouti
        Africa/Kampala Africa/Mogadishu Africa/Nairobi Indian/Antananarivo Indian/Comoro Indian/Mayotte ;
    EET 120 |
        Africa/Tripoli Europe/Kaliningrad Libya ;
    EET 120 EEST 180 |
        Africa/Cairo Asia/Beirut Asia/Famagusta Asia/Gaza Asia/Hebron Asia/Nicosia EET Egypt
        Europe/Athens Europe/Bucharest Europe/Chisinau Europe/Helsinki Europe/Kiev Europe/Kyiv
        Europe/Mariehamn Europe/Nicosia Europe/Riga Europe/Sofia Europe/Tallinn Europe/Tiraspol
        Europe/Uzhgorod Europe/Vilnius Europe/Zaporozhye ;
    EST -300 |
        America/Atikokan America/Cancun America/Cayman America/Coral_Harbour America/Jamaica
        America/Panama Jamaica ;
    EST -300 EDT -240 |
        America/Detroit America/Fort_Wayne America/Grand_Turk America/Indiana/Indianapolis
        America/Indiana/Marengo America/Indiana/Petersburg America/Indiana/Vevay
        America/Indiana/Vincennes America/Indiana/Winamac America/Indianapolis America/Iqaluit
        America/Kentucky/Louisville America/Kentucky/Monticello America/Louisville America/Montreal
        America/Nassau America/New_York America/Nipigon America/Pangnirtung America/Port-au-Prince
        America/Thunder_Bay America/Toronto Canada/Eastern US/East-Indiana US/Eastern US/Michigan ;
    GMT 0 |
        Africa/Abidjan Africa/Accra Africa/Bamako Africa/Banjul Africa/Bissau Africa/Conakry
        Africa/Dakar Africa/Freetown Africa/Lome Africa/Monrovia Africa/Nouakchott Africa/Ouagadougou
        Africa/Sao_Tome Africa/Timbuktu America/Danmarkshavn Antarctica/Troll Atlantic/Reykjavik
        Atlantic/St_Helena Etc/GMT Etc/GMT+0 Etc/GMT-0 Etc/GMT0 Etc/Greenwich GMT GMT0 Greenwich Iceland
        UTC ;
    GST 240 |
        Asia/Dubai Asia/Muscat ;
    HAST -600 HADT -540 |
        America/Adak America/Atka US/Aleutian ;
    HST -600 |
        Pacific/Honolulu Pacific/Johnston US/Hawaii ;
    IST 330 |
        Asia/Calcutta Asia/Colombo Asia/Kolkata ;
    IST 60 |
        Eire Europe/Dublin ;
    LHDT 660 LHST 630 |
        Australia/LHI Australia/Lord_Howe ;
    MST -420 |
        America/Creston America/Dawson_Creek America/Fort_Nelson America/Phoenix US/Arizona ;
    MST -420 MDT -360 |
        America/Boise America/Cambridge_Bay America/Ciudad_Juarez America/Denver America/Edmonton
        America/Inuvik America/Shiprock America/Yellowknife Canada/Mountain Navajo US/Mountain ;
    MYT 480 |
        Asia/Kuala_Lumpur Asia/Kuching ;
    NST -210 NDT -150 |
        America/St_Johns Canada/Newfoundland ;
    NZDT 780 NZST 720 |
        Antarctica/McMurdo Antarctica/South_Pole NZ Pacific/Auckland ;
    PMST -180 PMDT -120 |
        America/Miquelon ;
    PST -480 PDT -420 |
        America/Ensenada America/Los_Angeles America/Santa_Isabel America/Tijuana America/Vancouver
        Canada/Pacific Mexico/BajaNorte US/Pacific ;
    SAST 120 |
        Africa/Johannesburg Africa/Maseru Africa/Mbabane ;
    SGT 480 |
        Asia/Singapore Singapore ;
    UTC 0 |
        Etc/UCT Etc/UTC Etc/Universal Etc/Zulu UCT Universal Zulu ;
    WAT 60 |
        Africa/Bangui Africa/Brazzaville Africa/Douala Africa/Kinshasa Africa/Lagos Africa/Libreville
        Africa/Luanda Africa/Malabo Africa/Ndjamena Africa/Niamey Africa/Porto-Novo ;
    WET 0 WEST 60 |
        Atlantic/Canary Atlantic/Faeroe Atlantic/Faroe Atlantic/Madeira Europe/Lisbon Portugal WET ;
"""
