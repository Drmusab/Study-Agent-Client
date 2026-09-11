package com.studyagent.client.core.voice.stt

/**
 * Chooses which recognizer hypothesis represents the turn (§12/§39).
 *
 * For answers this is deliberately boring: rank 0 is the recognizer's own best guess and
 * the PC-side LLM evaluator is what judges correctness, so "correcting" the transcript here
 * would only hide recognition uncertainty from the component that can actually handle it
 * (§38). Alternatives are retained on the [RecognitionOutcome] as metadata for a future
 * protocol that sends them upstream.
 *
 * For ratings and commands the choice is *not* rank 0: a grammar-matching alternative is
 * preferred over a top-ranked word that matches nothing, which is what turns
 * `["could", "good", "hood"]` into `Good` while a rating is expected (§106).
 */
class RecognitionCandidateSelector(
    private val grammar: VoiceCommandGrammar = VoiceCommandGrammar()
) {

    /**
     * Best hypothesis for an answer-like purpose: the lowest-ranked non-blank candidate,
     * full stop.
     *
     * Rank 0 is the recognizer's own best guess, and the PC-side evaluator is what judges
     * correctness. `EXTRA_CONFIDENCE_SCORES` are not documented as comparable *across*
     * alternatives, so swapping in a different word because it scored higher would be
     * exactly the silent transcript alteration the thin-client split forbids — and in a
     * medical answer ("could" -> "good") that is a clinical risk, not a tidy-up.
     *
     * The alternatives are retained on [RecognitionOutcome] as metadata for a future
     * protocol that sends them upstream, where something can actually reason about them.
     */
    fun selectForAnswer(hypotheses: List<RecognitionHypothesis>): RecognitionHypothesis? =
        hypotheses.filterNot { it.isBlank }.minByOrNull { it.rank }

    /**
     * Best hypothesis for a command/rating purpose.
     *
     * Deterministic, in order:
     *  1. Highest-confidence EXACT grammar match.
     *  2. Highest-confidence HIGH grammar match.
     *  3. Any AMBIGUOUS match (returned so the caller can ask for confirmation).
     *  4. `null` — the utterance matched no command at all.
     *
     * Candidates with a reported confidence below [minConfidence] are skipped, which is how
     * a 0.2-confidence "easy" in a noisy room stops rescheduling cards (§107).
     */
    fun selectForCommand(
        hypotheses: List<RecognitionHypothesis>,
        minConfidence: Float,
        allowAnswerSafeOnly: Boolean = false
    ): ParsedVoiceCommand? {
        val parsed = hypotheses
            .filterNot { it.isBlank }
            .mapNotNull { hypothesis ->
                val normalized = CommandNormalizer.forCommand(hypothesis.text)
                // A long utterance is prose, not a command — never even try to match it.
                if (CommandNormalizer.looksLikeLongUtterance(hypothesis.text)) return@mapNotNull null
                grammar.parse(normalized, hypothesis.text, hypothesis)?.let { match ->
                    if (allowAnswerSafeOnly && !isAnswerSafe(match)) null else match
                }
            }

        val acceptable = parsed.filter { passesConfidence(it, minConfidence) }

        return acceptable.firstOrNull { it.confidence == CommandConfidence.EXACT }
            ?.let { bestOf(acceptable.filter { c -> c.confidence == CommandConfidence.EXACT }) }
            ?: acceptable.firstOrNull { it.confidence == CommandConfidence.HIGH }
                ?.let { bestOf(acceptable.filter { c -> c.confidence == CommandConfidence.HIGH }) }
            ?: parsed.firstOrNull { it.confidence == CommandConfidence.AMBIGUOUS }
    }

    /** Every distinct command any hypothesis maps to — used to build a confirmation prompt. */
    fun commandCandidates(hypotheses: List<RecognitionHypothesis>): List<ParsedVoiceCommand> =
        hypotheses
            .filterNot { it.isBlank }
            .mapNotNull { hypothesis ->
                val normalized = CommandNormalizer.forCommand(hypothesis.text)
                grammar.parse(normalized, hypothesis.text, hypothesis)
            }
            .groupBy { it.command.commandName }
            .values
            .map { group -> group.maxByOrNull { confidenceOrZero(it) } ?: group.first() }

    private fun isAnswerSafe(match: ParsedVoiceCommand): Boolean =
        match.matchedPhrase.isNotEmpty() && match.matchedPhrase in grammar.answerSafePhrases()

    private fun bestOf(candidates: List<ParsedVoiceCommand>): ParsedVoiceCommand =
        candidates.maxByOrNull { confidenceOrZero(it) } ?: candidates.first()

    /**
     * A `null` confidence means the provider did not report scores at all (common below
     * API 34 and on many recognizers). That must not make every command impossible, so
     * grammar strength alone decides — but a *reported* low score is always honoured.
     */
    private fun passesConfidence(match: ParsedVoiceCommand, minConfidence: Float): Boolean {
        if (match.confidence == CommandConfidence.AMBIGUOUS) return false
        val score = match.sourceHypothesis?.confidence ?: return true
        return score >= minConfidence
    }

    private fun confidenceOrZero(match: ParsedVoiceCommand): Float =
        match.sourceHypothesis?.confidence ?: 0f

}
