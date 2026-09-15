package com.myapp.expensetracker

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The SMS parsing rules, loaded from a versioned file rather than compiled in,
 * so filters can be corrected without shipping an APK.
 *
 * Every field is nullable at the parse boundary: this file can be edited in a
 * public repo, and a malformed or hostile edit must be rejected rather than
 * disabling transaction detection on someone's phone.
 */
data class ExtractionRulesFile(
    val schemaVersion: Int? = null,
    val version: Int? = null,
    val releasedAt: String? = null,
    val notes: String? = null,
    val spendKeywords: List<String?>? = null,
    val receiveKeywords: List<String?>? = null,
    val weakDirectionKeywords: List<String?>? = null,
    val otpPhrases: List<String?>? = null,
    val nonTransactionalPhrases: List<String?>? = null,
    val txnDisqualifiers: List<String?>? = null,
    val creditCardBillPhrases: List<String?>? = null,
    val amountPatterns: List<String?>? = null,
    val balanceLookback: Int? = null,
    // Element types are nullable throughout: Gson bypasses Kotlin's null checks,
    // so a hand-edited `[null]` in the published file would otherwise NPE here.
    val categories: Map<String, List<String?>?>? = null
)

/** A validated, ready-to-use rule set. */
data class ExtractionRules(
    val version: Int,
    val releasedAt: String,
    val spendKeywords: List<String>,
    val receiveKeywords: List<String>,
    /**
     * Keywords that prove a transaction happened but not which way the money
     * went ("txn", "payment", "transferred"). They stay in the spend/receive
     * lists so a message carrying nothing else still gets a direction, but they
     * lose to an unambiguous marker when both directions match.
     */
    val weakDirectionKeywords: List<String>,
    val otpPhrases: List<String>,
    val nonTransactionalPhrases: List<String>,
    val txnDisqualifiers: List<String>,
    val creditCardBillPhrases: List<String>,
    val amountPatterns: List<Regex>,
    val balanceLookback: Int,
    val categories: Map<String, List<String>>
)

object ExtractionRulesParser {

    const val SUPPORTED_SCHEMA_VERSION = 1

    private const val MAX_PATTERNS = 32
    private const val MAX_PATTERN_LENGTH = 300
    private const val MAX_TERMS_PER_LIST = 500

    sealed interface Result {
        data class Success(val rules: ExtractionRules) : Result
        data class Failure(val reason: String) : Result
    }

    fun parse(json: String): Result {
        val file = try {
            Gson().fromJson(json, ExtractionRulesFile::class.java)
        } catch (e: Exception) {
            return Result.Failure("File isn't valid JSON.")
        } ?: return Result.Failure("File is empty.")

        val schema = file.schemaVersion ?: 0
        if (schema > SUPPORTED_SCHEMA_VERSION) {
            return Result.Failure(
                "Rules use format v$schema; this app understands up to v$SUPPORTED_SCHEMA_VERSION. Update the app first."
            )
        }

        val version = file.version
            ?: return Result.Failure("Rules are missing a version number.")

        // Detection breaks entirely without these two, so they are mandatory.
        val spend = file.spendKeywords.clean()
        if (spend.isEmpty()) return Result.Failure("No spend keywords defined.")
        val receive = file.receiveKeywords.clean()
        if (receive.isEmpty()) return Result.Failure("No receive keywords defined.")

        val rawPatterns = file.amountPatterns.clean()
        if (rawPatterns.isEmpty()) return Result.Failure("No amount patterns defined.")
        if (rawPatterns.size > MAX_PATTERNS) return Result.Failure("Too many amount patterns.")

        val patterns = mutableListOf<Regex>()
        for (pattern in rawPatterns) {
            if (pattern.length > MAX_PATTERN_LENGTH) {
                return Result.Failure("An amount pattern is unreasonably long.")
            }
            val compiled = try {
                Regex(pattern, RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                return Result.Failure("An amount pattern isn't a valid regex.")
            }
            // Every pattern must expose the amount as group 1.
            if (!pattern.contains("(")) {
                return Result.Failure("An amount pattern has no capture group.")
            }
            patterns += compiled
        }

        val lookback = file.balanceLookback ?: 15
        if (lookback !in 0..200) return Result.Failure("balanceLookback is out of range.")

        val categories = file.categories.orEmpty()
            .mapValues { (_, keywords) -> keywords.clean() }
            .filterValues { it.isNotEmpty() }

        return Result.Success(
            ExtractionRules(
                version = version,
                releasedAt = file.releasedAt?.takeIf { it.isNotBlank() } ?: "unknown",
                spendKeywords = spend,
                receiveKeywords = receive,
                // Optional: absent in v1 files, and an old app reading a newer
                // file simply ignores it and keeps the pre-tie-break behaviour.
                weakDirectionKeywords = file.weakDirectionKeywords.clean(),
                otpPhrases = file.otpPhrases.clean(),
                nonTransactionalPhrases = file.nonTransactionalPhrases.clean(),
                txnDisqualifiers = file.txnDisqualifiers.clean(),
                creditCardBillPhrases = file.creditCardBillPhrases.clean(),
                amountPatterns = patterns,
                balanceLookback = lookback,
                categories = categories
            )
        )
    }

    private fun List<String?>?.clean(): List<String> =
        orEmpty().mapNotNull { it?.trim()?.takeIf { t -> t.isNotBlank() } }
            .take(MAX_TERMS_PER_LIST)
}

/**
 * Holds the rule set currently in force. Initialised once at app startup so the
 * broadcast receivers, which construct [TransactionExtractor] on the fly, don't
 * each need a Context.
 */
object ExtractionRulesRepository {

    @Volatile
    private var cached: ExtractionRules? = null

    /** Never null: falls back to the compiled-in defaults before initialisation. */
    fun current(): ExtractionRules = cached ?: DefaultExtractionRules.rules

    /** Test seam: installs a rule set directly, bypassing asset/file loading. */
    internal fun install(rules: ExtractionRules?) {
        cached = rules
    }

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        val active = runCatching {
            RemoteResourceLoader.readActive(appContext, RemoteResource.EXTRACTION_RULES)
        }.getOrNull() ?: return

        when (val result = ExtractionRulesParser.parse(active)) {
            is ExtractionRulesParser.Result.Success -> cached = result.rules
            is ExtractionRulesParser.Result.Failure -> {
                AppLog.e("ExtractionRules", "Active rules rejected: ${result.reason}")
                // A previously downloaded file has gone bad — drop back to the
                // baseline that shipped with this build.
                RemoteResourceLoader.revertToBundled(appContext, RemoteResource.EXTRACTION_RULES)
                runCatching {
                    RemoteResourceLoader.readBundled(appContext, RemoteResource.EXTRACTION_RULES)
                }.getOrNull()?.let { bundled ->
                    (ExtractionRulesParser.parse(bundled) as? ExtractionRulesParser.Result.Success)
                        ?.let { cached = it.rules }
                }
            }
        }
    }

    sealed interface UpdateOutcome {
        data class Updated(val from: Int, val to: Int, val releasedAt: String) : UpdateOutcome
        data class AlreadyCurrent(val version: Int) : UpdateOutcome
        data class Failed(val reason: String) : UpdateOutcome
    }

    /** Fetches the published rules and adopts them only if they validate. */
    suspend fun update(context: Context): UpdateOutcome = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val previous = current()

        val fetched = RemoteResourceLoader.fetch(RemoteResource.EXTRACTION_RULES) { text ->
            ExtractionRulesParser.parse(text) is ExtractionRulesParser.Result.Success
        }

        val text = fetched.getOrElse { error ->
            return@withContext UpdateOutcome.Failed(
                error.message ?: "Could not download the rules."
            )
        }

        when (val parsed = ExtractionRulesParser.parse(text)) {
            is ExtractionRulesParser.Result.Failure ->
                UpdateOutcome.Failed(parsed.reason)

            is ExtractionRulesParser.Result.Success -> {
                if (parsed.rules.version < previous.version) {
                    return@withContext UpdateOutcome.Failed(
                        "Published rules (v${parsed.rules.version}) are older than the ones in use (v${previous.version})."
                    )
                }
                if (parsed.rules.version == previous.version) {
                    return@withContext UpdateOutcome.AlreadyCurrent(previous.version)
                }
                RemoteResourceLoader.persist(appContext, RemoteResource.EXTRACTION_RULES, text)
                cached = parsed.rules
                UpdateOutcome.Updated(
                    from = previous.version,
                    to = parsed.rules.version,
                    releasedAt = parsed.rules.releasedAt
                )
            }
        }
    }

    fun revert(context: Context) {
        val appContext = context.applicationContext
        RemoteResourceLoader.revertToBundled(appContext, RemoteResource.EXTRACTION_RULES)
        cached = null
        initialize(appContext)
    }
}
