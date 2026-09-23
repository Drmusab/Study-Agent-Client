package com.studyagent.client.ui.components.anki

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardMetadata
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiMediaRef
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.AnkiRenderedCard

/**
 * GATE 08 STEP 98 — the rendering compatibility fixture deck.
 *
 * ## Why this lives in `src/debug`
 *
 * It is test and preview material, not product content: a release build must not ship a deck of
 * synthetic cards. `src/debug` is compiled into the debug variant, which is exactly the variant both
 * `testDebugUnitTest` and `androidTest` compile against — so the JVM suites, the instrumented WebView
 * suites and the Compose previews share **one** fixture definition instead of three drifting copies.
 *
 * ## What the fixtures are
 *
 * Each one is an [AnkiRenderedCard] exactly as GATE 07 would hand it over from the pinned AnkiDroid
 * card contract: `questionHtml`/`answerHtml` are **rendered body fragments** (no `<!doctype>`, no
 * note-type `<style>` block), and the answer side contains the question through `{{FrontSide}}`
 * followed by `<hr id=answer>` — the semantics the renderer must not second-guess (STEP 27/§28,
 * INV-ANKI-RENDER-12). The `*_simple` channels carry the backend's text view.
 *
 * Nothing here is AnkiDroid-internal: no reviewer JavaScript, no private CSS, no copied template files
 * (STEP 96/§97, INV-ANKI-RENDER-10/21). Every string is written for this repository.
 *
 * ## The matrix (STEP 98, PART III §W)
 *
 * | Fixture | GATE 08 expectation |
 * |---|---|
 * | [BASIC], [HTML_FORMATTING], [NESTED_DIVS] | PASS |
 * | [CSS_CARD_CLASS], [LIGHT_AUTHORED_CARD], [DARK_AUTHORED_CARD] | PASS |
 * | [TABLE], [WIDE_TABLE] | PASS (a wide table scrolls horizontally) |
 * | [CLOZE] | PASS (rendered cloze, no local cloze logic) |
 * | [ARABIC_RTL], [ARABIC_NO_DIR], [MIXED_ARABIC_ENGLISH] | PASS |
 * | [LONG_QUESTION], [LONG_ANSWER] | PASS (scrollable) |
 * | [UNICODE_SYMBOLS], [EMOJI] | PASS |
 * | [JS_ON_LOAD], [JS_ON_CLICK] | PASS under `CARD_TEMPLATE_ONLY`, no native bridge |
 * | [JS_ANKIDROID_API] | PASS = survives; the API is absent by design (STEP 51) |
 * | [LINKS] | PASS (https mediated, anchor in-page, unknown schemes blocked) |
 * | [FULL_DOCUMENT] | PASS (verbatim, never double-wrapped) |
 * | [IMAGE_REF], [AUDIO_TAG], [MATH_MARKUP], [CUSTOM_FONT] | DEFERRED to GATE 09: reference preserved, no resolution |
 * | [VISUAL_ONLY], [TEXT_ONLY], [ANSWER_WITHOUT_CONTENT] | PASS (documented fallback / typed failure) |
 */
object AnkiCardRenderFixtures {

    /** What GATE 08 claims for one fixture: verified here, or deliberately deferred to GATE 09. */
    enum class Expectation {
        PASS,
        DEFERRED_GATE_09
    }

    data class Fixture(
        val id: String,
        val label: String,
        val expectation: Expectation,
        val card: AnkiRenderedCard
    )

    private val backend = AnkiBackendId.Fake("render-fixtures")
    private val collectionKey = "fixture-collection"
    private val deckRef = AnkiDeckRef(backend, "deck-render", collectionKey)

    // ------------------------------------------------------------------ PASS fixtures

    /** Plain text, no markup at all — the floor every renderer must clear. */
    val BASIC = fixture(
        id = "basic",
        label = "Basic text",
        expectation = Expectation.PASS,
        questionHtml = "What is the normal resting heart rate in adults?",
        answerHtml = "What is the normal resting heart rate in adults?\n\n<hr id=answer>\n60-100 beats per minute",
        questionText = "What is the normal resting heart rate in adults?",
        answerText = "What is the normal resting heart rate in adults? 60-100 beats per minute",
        pureAnswerText = "60-100 beats per minute"
    )

    /** Bold, italic, underline, lists, line breaks, superscript/subscript — inline semantics. */
    val HTML_FORMATTING = fixture(
        id = "html_formatting",
        label = "HTML formatting",
        expectation = Expectation.PASS,
        questionHtml = """
            <b>Which</b> <i>three</i> findings define <u>nephrotic</u> syndrome?<br>
            <ol><li>Proteinuria &gt; 3.5 g/day</li><li>Hypoalbuminaemia</li><li>Oedema</li></ol>
            Normal HCO<sub>3</sub><sup>-</sup> is 22-28 mmol/L.
        """.trimIndent(),
        answerHtml = """
            <b>Which</b> <i>three</i> findings define <u>nephrotic</u> syndrome?<br>
            <ol><li>Proteinuria &gt; 3.5 g/day</li><li>Hypoalbuminaemia</li><li>Oedema</li></ol>
            <hr id=answer>
            <ul><li><b>Proteinuria</b> &gt; 3.5 g/day</li><li><b>Hypoalbuminaemia</b></li>
            <li><b>Oedema</b></li><li>Hyperlipidaemia (supportive)</li></ul>
        """.trimIndent(),
        questionText = "Which three findings define nephrotic syndrome?",
        answerText = "Proteinuria greater than 3.5 g/day, hypoalbuminaemia and oedema.",
        pureAnswerText = "Proteinuria greater than 3.5 g/day, hypoalbuminaemia and oedema."
    )

    /** Nested divs with classes and ids: structure a template's CSS may depend on. */
    val NESTED_DIVS = fixture(
        id = "nested_divs",
        label = "Nested divs",
        expectation = Expectation.PASS,
        questionHtml = """
            <div class="stem" id="stem-1">
              <div class="vignette"><p>A 54-year-old man presents with&hellip;</p></div>
              <div class="lead-in"><p>Which structure is affected?</p></div>
            </div>
        """.trimIndent(),
        answerHtml = """
            <div class="stem" id="stem-1">
              <div class="vignette"><p>A 54-year-old man presents with&hellip;</p></div>
              <div class="lead-in"><p>Which structure is affected?</p></div>
            </div>
            <hr id=answer>
            <div class="answer-block"><div class="answer-text">Left anterior descending artery</div></div>
        """.trimIndent(),
        questionText = "A 54-year-old man presents. Which structure is affected?",
        answerText = "Left anterior descending artery",
        pureAnswerText = "Left anterior descending artery"
    )

    /**
     * A template that ships its own `<style>` block, including a `.card` rule.
     *
     * This is the CSS-priority test (STEP 24/§25, INV-ANKI-RENDER-14): the fragment's rules come after
     * the renderer's base stylesheet in document order, so they must win — centred text, a serif face,
     * the authored background.
     */
    val CSS_CARD_CLASS = fixture(
        id = "css_card_class",
        label = "Template CSS with .card rule",
        expectation = Expectation.PASS,
        questionHtml = """
            <style>
              .card { font-family: Georgia, serif; font-size: 22px; text-align: center;
                      background-color: #FFF8E7; color: #22303C; padding: 24px; }
              .hint { color: #6B7A8F; font-size: 14px; }
              #unique { letter-spacing: 2px; }
            </style>
            <div id="unique">Which valve is most often affected by rheumatic heart disease?</div>
            <div class="hint">Think left side, think stenosis.</div>
        """.trimIndent(),
        answerHtml = """
            <style>
              .card { font-family: Georgia, serif; font-size: 22px; text-align: center;
                      background-color: #FFF8E7; color: #22303C; padding: 24px; }
              .hint { color: #6B7A8F; font-size: 14px; }
            </style>
            <div>Which valve is most often affected by rheumatic heart disease?</div>
            <hr id=answer>
            <div class="hint">Mitral valve</div>
        """.trimIndent(),
        questionText = "Which valve is most often affected by rheumatic heart disease?",
        answerText = "Mitral valve",
        pureAnswerText = "Mitral valve"
    )

    /** Anki's own default-looking card styling: light card inside a dark app (STEP 40/§41). */
    val LIGHT_AUTHORED_CARD = fixture(
        id = "light_authored",
        label = "Card-authored light theme",
        expectation = Expectation.PASS,
        questionHtml = """
            <style>.card { background-color: white; color: black; font-family: arial;
                    font-size: 20px; text-align: center; }</style>
            <div>Name the four lobes of the liver.</div>
        """.trimIndent(),
        answerHtml = """
            <style>.card { background-color: white; color: black; font-family: arial;
                    font-size: 20px; text-align: center; }</style>
            <div>Name the four lobes of the liver.</div>
            <hr id=answer>
            <div>Right, left, caudate, quadrate</div>
        """.trimIndent(),
        questionText = "Name the four lobes of the liver.",
        answerText = "Right, left, caudate and quadrate lobes.",
        pureAnswerText = "Right, left, caudate and quadrate lobes."
    )

    /** A card that already ships a dark design: night mode must not invert or fight it (STEP 42). */
    val DARK_AUTHORED_CARD = fixture(
        id = "dark_authored",
        label = "Card-authored dark theme",
        expectation = Expectation.PASS,
        questionHtml = """
            <style>.card { background-color: #101418; color: #E6EDF3; padding: 20px; }
                    .accent { color: #4FD1C5; }</style>
            <div class="accent">What does an S3 gallop indicate?</div>
        """.trimIndent(),
        answerHtml = """
            <style>.card { background-color: #101418; color: #E6EDF3; padding: 20px; }
                    .accent { color: #4FD1C5; }</style>
            <div class="accent">What does an S3 gallop indicate?</div>
            <hr id=answer>
            <div>Volume overload / systolic dysfunction (in an adult over 40).</div>
        """.trimIndent(),
        questionText = "What does an S3 gallop indicate?",
        answerText = "Volume overload or systolic dysfunction in an adult over forty.",
        pureAnswerText = "Volume overload or systolic dysfunction in an adult over forty."
    )

    /** An ordinary bordered table. */
    val TABLE = fixture(
        id = "table",
        label = "Table",
        expectation = Expectation.PASS,
        questionHtml = """
            <table border="1" cellpadding="6">
              <thead><tr><th>Cranial nerve</th><th>Nucleus level</th></tr></thead>
              <tbody>
                <tr><td>III Oculomotor</td><td>Midbrain</td></tr>
                <tr><td>VII Facial</td><td>Pons</td></tr>
                <tr><td>XII Hypoglossal</td><td>Medulla</td></tr>
              </tbody>
            </table>
            <p>Which nerve arises at the pontomedullary junction?</p>
        """.trimIndent(),
        answerHtml = """
            <table border="1" cellpadding="6">
              <thead><tr><th>Cranial nerve</th><th>Nucleus level</th></tr></thead>
              <tbody>
                <tr><td>III Oculomotor</td><td>Midbrain</td></tr>
                <tr><td>VII Facial</td><td>Pons</td></tr>
                <tr><td>XII Hypoglossal</td><td>Medulla</td></tr>
              </tbody>
            </table>
            <hr id=answer>
            <p>VI Abducens (and VII/VIII at the junction).</p>
        """.trimIndent(),
        questionText = "Which nerve arises at the pontomedullary junction?",
        answerText = "The abducens nerve, with the facial and vestibulocochlear nerves at the junction.",
        pureAnswerText = "Abducens nerve."
    )

    /** A deliberately wide table: must stay usable and must not break the Compose hierarchy (STEP 63/§64). */
    val WIDE_TABLE = fixture(
        id = "wide_table",
        label = "Wide table (12 columns)",
        expectation = Expectation.PASS,
        questionHtml = wideTableHtml(),
        answerHtml = wideTableHtml() + "\n<hr id=answer>\n<p>Column 12 of row 8: <b>value-8-12</b></p>",
        questionText = "Antibiotic susceptibility table: what is the value in the last column of row eight?",
        answerText = "Value 8 12.",
        pureAnswerText = "Value 8 12."
    )

    /**
     * Rendered cloze output (STEP 21, PART III §W).
     *
     * The question side shows the Anki-rendered `[...]` placeholder; the answer side shows the revealed
     * value with `class="cloze"`. The renderer displays both **as produced** — no local cloze logic, no
     * re-expansion of `{{c1::…}}` (INV-ANKI-RENDER-11).
     */
    val CLOZE = fixture(
        id = "cloze",
        label = "Cloze (rendered)",
        expectation = Expectation.PASS,
        questionHtml = """The <span class="cloze">[…]</span> is the valve between the left atrium and left ventricle.""",
        answerHtml = """The <span class="cloze">mitral valve</span> is the valve between the left atrium and left ventricle.""",
        questionText = "The [...] is the valve between the left atrium and left ventricle.",
        answerText = "The mitral valve is the valve between the left atrium and left ventricle.",
        pureAnswerText = "mitral valve"
    )

    /** Arabic with an authored `dir="rtl"`: the card's own direction must be respected (STEP 36). */
    val ARABIC_RTL = fixture(
        id = "arabic_rtl",
        label = "Arabic (authored RTL)",
        expectation = Expectation.PASS,
        questionHtml = """<div dir="rtl"><b>ما هو معدل ضربات القلب الطبيعي عند البالغين؟</b></div>""",
        answerHtml = """<div dir="rtl"><b>ما هو معدل ضربات القلب الطبيعي عند البالغين؟</b></div>
            <hr id=answer>
            <div dir="rtl">من ٦٠ إلى ١٠٠ نبضة في الدقيقة.</div>""".trimIndent(),
        questionText = "ما هو معدل ضربات القلب الطبيعي عند البالغين؟",
        answerText = "من ٦٠ إلى ١٠٠ نبضة في الدقيقة.",
        pureAnswerText = "من ٦٠ إلى ١٠٠ نبضة في الدقيقة."
    )

    /**
     * Arabic with **no** direction markup: `dir="auto"` must resolve the base direction from the content
     * (STEP 35/§37) — nothing may hard-code `ltr` here.
     */
    val ARABIC_NO_DIR = fixture(
        id = "arabic_no_dir",
        label = "Arabic (no authored direction)",
        expectation = Expectation.PASS,
        questionHtml = "<p>اذكر أسباب قصور القلب الانقباضي.</p>",
        answerHtml = "<p>اذكر أسباب قصور القلب الانقباضي.</p>\n<hr id=answer>\n" +
            "<ul><li>احتشاء عضلة القلب</li><li>ارتفاع ضغط الدم المزمن</li><li>اعتلال عضلة القلب</li></ul>",
        questionText = "اذكر أسباب قصور القلب الانقباضي.",
        answerText = "احتشاء عضلة القلب، ارتفاع ضغط الدم المزمن، اعتلال عضلة القلب.",
        pureAnswerText = "احتشاء عضلة القلب، ارتفاع ضغط الدم المزمن، اعتلال عضلة القلب."
    )

    /** Mixed Arabic/English with numerals and medical abbreviations (STEP 37/§116). */
    val MIXED_ARABIC_ENGLISH = fixture(
        id = "mixed_arabic_english",
        label = "Mixed Arabic / English",
        expectation = Expectation.PASS,
        questionHtml = """<div dir="rtl">مريض عمره 54 سنة لديه <b>STEMI</b> في الجدار السفلي،
            ما هو الشريان المسؤول غالبًا؟</div>""".trimIndent(),
        answerHtml = """<div dir="rtl">مريض عمره 54 سنة لديه <b>STEMI</b> في الجدار السفلي،
            ما هو الشريان المسؤول غالبًا؟</div>
            <hr id=answer>
            <div dir="rtl">الشريان التاجي الأيمن (<span dir="ltr">RCA</span>) في 80% من الحالات،
            وقد يكون الشريان المنعطف (<span dir="ltr">LCx</span>) في 20%.</div>""".trimIndent(),
        questionText = "مريض عمره 54 سنة لديه STEMI في الجدار السفلي، ما هو الشريان المسؤول غالبًا؟",
        answerText = "الشريان التاجي الأيمن RCA في ثمانين بالمئة من الحالات.",
        pureAnswerText = "Right coronary artery (RCA)."
    )

    /** A long question: scrolling inside the WebView, no truncation (STEP 118). */
    val LONG_QUESTION = fixture(
        id = "long_question",
        label = "Long question",
        expectation = Expectation.PASS,
        questionHtml = longTextHtml("Paragraph", 40),
        answerHtml = longTextHtml("Paragraph", 40) + "\n<hr id=answer>\n<p><b>Answer:</b> the last paragraph.</p>",
        questionText = "A long clinical vignette spanning forty paragraphs.",
        answerText = "The last paragraph.",
        pureAnswerText = "The last paragraph."
    )

    /** A long answer: the reveal must stay scrollable and responsive (STEP 118/§118). */
    val LONG_ANSWER = fixture(
        id = "long_answer",
        label = "Long answer",
        expectation = Expectation.PASS,
        questionHtml = "<p>List the differential diagnosis for an elevated anion gap metabolic acidosis.</p>",
        answerHtml = "<p>List the differential diagnosis for an elevated anion gap metabolic acidosis.</p>\n" +
            "<hr id=answer>\n" + longTextHtml("Cause", 60),
        questionText = "List the differential diagnosis for an elevated anion gap metabolic acidosis.",
        answerText = "MUDPILES and GOLDMARK causes.",
        pureAnswerText = "MUDPILES and GOLDMARK causes."
    )

    /** Greek, mathematical relations, arrows, micro sign and Arabic diacritics (STEP 79, INV-RENDER-27). */
    val UNICODE_SYMBOLS = fixture(
        id = "unicode_symbols",
        label = "Unicode / math symbols",
        expectation = Expectation.PASS,
        questionHtml = """<p>Given &alpha; &beta; &gamma; &Delta;, is &mu; &le; 0.05 or &ge; 0.05?
            Does Na<sup>+</sup> &rarr; K<sup>+</sup> exchange matter?</p>""".trimIndent(),
        answerHtml = """<p>Given &alpha; &beta; &gamma; &Delta;, is &mu; &le; 0.05 or &ge; 0.05?</p>
            <hr id=answer>
            <p>&mu; &le; 0.05 &rArr; reject H&#8320;. Arabic diacritics: الْعِلْمُ نُورٌ. Entity test: &amp;amp; stays literal.</p>""".trimIndent(),
        questionText = "Given alpha beta gamma delta, is mu less than or equal to 0.05?",
        answerText = "Mu is less than or equal to 0.05, so reject the null hypothesis.",
        pureAnswerText = "Reject the null hypothesis."
    )

    /** Emoji must survive UTF-8 loading unchanged (STEP 79). */
    val EMOJI = fixture(
        id = "emoji",
        label = "Emoji",
        expectation = Expectation.PASS,
        questionHtml = "<p>Which emoji means 🧠 and which means ❤️‍🩹? 😀🩺💊🏥</p>",
        answerHtml = "<p>Which emoji means 🧠 and which means ❤️‍🩹? 😀🩺💊🏥</p>\n<hr id=answer>\n" +
            "<p>🧠 brain, ❤️‍🩹 mending heart.</p>",
        questionText = "Which emoji means brain and which means mending heart?",
        answerText = "Brain and mending heart.",
        pureAnswerText = "Brain and mending heart."
    )

    /** A card script that runs on load and rewrites visible text (STEP 50, PART III §G). */
    val JS_ON_LOAD = fixture(
        id = "js_on_load",
        label = "JavaScript on load",
        expectation = Expectation.PASS,
        questionHtml = """
            <p id="computed">pending</p>
            <script>
              document.getElementById('computed').textContent = 'computed-by-card-script';
            </script>
        """.trimIndent(),
        answerHtml = """
            <p id="computed">pending</p>
            <script>
              document.getElementById('computed').textContent = 'computed-by-card-script';
            </script>
            <hr id=answer><p>Answer side</p>
        """.trimIndent(),
        questionText = "A card whose script computes the visible text on load.",
        answerText = "Answer side.",
        pureAnswerText = "Answer side."
    )

    /**
     * A card-local interactive widget (PART III §H): a button toggles a hidden explanation.
     *
     * It must work with `CARD_TEMPLATE_ONLY`, entirely inside the page — and it must find **no** native
     * object to call (STEP 09/§112/§113).
     */
    val JS_ON_CLICK = fixture(
        id = "js_on_click",
        label = "JavaScript interactive",
        expectation = Expectation.PASS,
        questionHtml = """
            <button type="button" id="toggle" onclick="toggleHint()">Show hint</button>
            <div id="hint" style="display:none">Hint: think of the mitral valve.</div>
            <script>
              function toggleHint() {
                var hint = document.getElementById('hint');
                var button = document.getElementById('toggle');
                var visible = hint.style.display !== 'none';
                hint.style.display = visible ? 'none' : 'block';
                button.textContent = visible ? 'Show hint' : 'Hide hint';
              }
            </script>
        """.trimIndent(),
        answerHtml = """
            <button type="button" id="toggle" onclick="toggleHint()">Show hint</button>
            <div id="hint" style="display:none">Hint: think of the mitral valve.</div>
            <hr id=answer><p>Mitral valve.</p>
        """.trimIndent(),
        questionText = "A card with a button that toggles a hidden hint.",
        answerText = "Mitral valve.",
        pureAnswerText = "Mitral valve."
    )

    /**
     * A card written against AnkiDroid's private JS API (STEP 51, INV-ANKI-RENDER-10).
     *
     * Study-Agent does **not** implement `AnkiDroidJsAPI`, so this script throws. The expectation is
     * that the card still displays, the error is counted as a renderer diagnostic, and the app does not
     * crash (STEP 54) — and that no rating or session mutation is reachable from the page (STEP 52/§53).
     */
    val JS_ANKIDROID_API = fixture(
        id = "js_ankidroid_api",
        label = "JavaScript expecting AnkiDroidJsAPI (absent by design)",
        expectation = Expectation.PASS,
        questionHtml = """
            <p id="api">api-unavailable</p>
            <script>
              try {
                var mark = AnkiDroidJSAPI.ankiGetCardMark();
                document.getElementById('api').textContent = 'api-answered';
              } catch (error) {
                document.getElementById('api').textContent = 'api-absent';
              }
            </script>
        """.trimIndent(),
        answerHtml = """<p id="api">api-absent</p><hr id=answer><p>Answer side.</p>""",
        questionText = "A card that expects the AnkiDroid JavaScript API.",
        answerText = "Answer side.",
        pureAnswerText = "Answer side."
    )

    /**
     * Every link shape a card may contain (STEP 44-§47, PART III §K/§L).
     *
     * Expected: the `https` link is mediated to an external browser and the card stays put; the `#`
     * anchor is handled in-page; `mailto:` and the custom scheme are blocked.
     */
    val LINKS = fixture(
        id = "links",
        label = "Links (external, anchor, mailto, unknown scheme)",
        expectation = Expectation.PASS,
        questionHtml = """
            <p><a href="https://example.com/guidelines">External guideline</a></p>
            <p><a href="#answer-section">Jump to answer section</a></p>
            <p><a href="mailto:someone@example.com">Email</a></p>
            <p><a href="studyagent-custom://open">Unknown scheme</a></p>
            <p><a href="relative-page.html">Relative link (renderer origin)</a></p>
            <div id="answer-section" style="height:800px">Spacer so the anchor has somewhere to go.</div>
        """.trimIndent(),
        answerHtml = """<p>Links fixture, answer side.</p><hr id=answer><p>Nothing to reveal.</p>""",
        questionText = "A card containing external, anchor, mailto and unknown-scheme links.",
        answerText = "Nothing to reveal.",
        pureAnswerText = "Nothing to reveal."
    )

    /**
     * A payload that already is a complete document (STEP 20/§22).
     *
     * GATE 07's AnkiDroid contract delivers fragments, but the renderer must not *assume* that: this
     * fixture proves a document-shaped payload is passed through verbatim instead of being nested inside
     * a second `<html>`, which is how `<body>` attributes and card CSS get silently lost.
     */
    val FULL_DOCUMENT = fixture(
        id = "full_document",
        label = "Complete HTML document (verbatim)",
        expectation = Expectation.PASS,
        questionHtml = """<!doctype html>
            <html dir="rtl" lang="ar">
            <head><meta charset="utf-8"><style>.card{font-size:24px}</style></head>
            <body class="card"><p>سؤال كامل المستند</p></body>
            </html>""".trimIndent(),
        answerHtml = "<p>Answer side is a fragment even when the question was a document.</p>",
        questionText = "A complete HTML document payload.",
        answerText = "Answer side.",
        pureAnswerText = "Answer side."
    )

    // ------------------------------------------------------------------ fallback fixtures

    /**
     * Visual-only card (GATE 07 `card_speech_text_unavailable`): HTML exists, the text channel does not.
     * ORIGINAL renders normally; a CLEAN request degrades to ORIGINAL, marked — never to stripped HTML.
     */
    val VISUAL_ONLY = fixture(
        id = "visual_only",
        label = "Visual only (no text channel)",
        expectation = Expectation.PASS,
        questionHtml = "<div class=\"diagram\"><table><tr><td>Visual-only question</td></tr></table></div>",
        answerHtml = "<div class=\"diagram\">Visual-only answer</div>",
        questionText = null,
        answerText = null,
        pureAnswerText = null,
        degradations = listOf("card_speech_text_unavailable")
    )

    /** Text-only card: no HTML channel at all → the documented CLEAN fallback (STEP 119). */
    val TEXT_ONLY = fixture(
        id = "text_only",
        label = "Text only (no HTML channel)",
        expectation = Expectation.PASS,
        questionHtml = null,
        answerHtml = null,
        questionText = "Which nerve is compressed in carpal tunnel syndrome?",
        answerText = "The median nerve.",
        pureAnswerText = "Median nerve.",
        degradations = listOf("card_visual_html_unavailable")
    )

    /** An answer side with neither channel: a typed failure, never a blank screen (STEP 120). */
    val ANSWER_WITHOUT_CONTENT = fixture(
        id = "answer_without_content",
        label = "Answer side without any content",
        expectation = Expectation.PASS,
        questionHtml = "<p>A question whose answer side has no representation.</p>",
        answerHtml = null,
        questionText = "A question whose answer side has no representation.",
        answerText = null,
        pureAnswerText = null
    )

    // ------------------------------------------------------------------ GATE 09 fixtures

    /** An image reference: preserved, unresolved, and the card must still render (STEP 59). */
    val IMAGE_REF = fixture(
        id = "image_ref",
        label = "Image reference (media resolution is GATE 09)",
        expectation = Expectation.DEFERRED_GATE_09,
        questionHtml = """<p>What does this radiograph show?</p>
            <img src="chest_xray_pneumonia.jpg" alt="Chest radiograph">""".trimIndent(),
        answerHtml = """<p>What does this radiograph show?</p>
            <img src="chest_xray_pneumonia.jpg" alt="Chest radiograph">
            <hr id=answer><p>Right lower lobe pneumonia.</p>""".trimIndent(),
        questionText = "What does this radiograph show?",
        answerText = "Right lower lobe pneumonia.",
        pureAnswerText = "Right lower lobe pneumonia.",
        media = listOf(AnkiMediaRef.BackendStream("chest_xray_pneumonia.jpg", "image/jpeg"))
    )

    /** An Anki sound tag and an `<audio>` element: playback is GATE 09 (STEP 60). */
    val AUDIO_TAG = fixture(
        id = "audio_tag",
        label = "Audio reference (playback is GATE 09)",
        expectation = Expectation.DEFERRED_GATE_09,
        questionHtml = """<p>Repeat the phrase: [sound:pronunciation_fr.mp3]</p>
            <audio controls src="pronunciation_fr.mp3"></audio>""".trimIndent(),
        answerHtml = """<p>Repeat the phrase: [sound:pronunciation_fr.mp3]</p><hr id=answer>
            <p>Le cœur.</p>""".trimIndent(),
        questionText = "Repeat the phrase.",
        answerText = "Le coeur.",
        pureAnswerText = "Le coeur.",
        media = listOf(AnkiMediaRef.BackendStream("pronunciation_fr.mp3", "audio/mpeg"))
    )

    /**
     * MathJax delimiters preserved exactly (STEP 62).
     *
     * GATE 08 keeps the markup and does not build a math engine; whether the MathJax script actually
     * loads and renders is a GATE 09 compatibility question (the renderer's base URL cannot serve it and
     * network image loads are blocked in this gate).
     */
    val MATH_MARKUP = fixture(
        id = "math_markup",
        label = "MathJax markup (full support is GATE 09)",
        expectation = Expectation.DEFERRED_GATE_09,
        questionHtml = """<p>Solve \( e^{i\pi} + 1 = x \) and express \[ \int_0^\infty e^{-x^2}\,dx \] exactly.</p>""",
        answerHtml = """<p>Solve \( e^{i\pi} + 1 = x \)</p><hr id=answer>
            <p>\( x = 0 \) and \( \int_0^\infty e^{-x^2}\,dx = \frac{\sqrt{\pi}}{2} \).</p>""".trimIndent(),
        questionText = "Solve e to the i pi plus one equals x.",
        answerText = "x equals zero.",
        pureAnswerText = "x = 0"
    )

    /** A custom font reference: markup preserved, resolution is GATE 09 (STEP 131). */
    val CUSTOM_FONT = fixture(
        id = "custom_font",
        label = "Custom font reference (resolution is GATE 09)",
        expectation = Expectation.DEFERRED_GATE_09,
        questionHtml = """
            <style>
              @font-face { font-family: 'StudyFont'; src: url('StudyFont.woff2') format('woff2'); }
              .fancy { font-family: 'StudyFont', serif; font-size: 26px; }
            </style>
            <div class="fancy">Which drug class causes a dry cough?</div>
        """.trimIndent(),
        answerHtml = """<div class="fancy">ACE inhibitors.</div>""",
        questionText = "Which drug class causes a dry cough?",
        answerText = "ACE inhibitors.",
        pureAnswerText = "ACE inhibitors.",
        media = listOf(AnkiMediaRef.BackendStream("StudyFont.woff2", "font/woff2"))
    )

    /** Every fixture, in matrix order. */
    val all: List<Fixture> = listOf(
        BASIC, HTML_FORMATTING, NESTED_DIVS, CSS_CARD_CLASS, LIGHT_AUTHORED_CARD, DARK_AUTHORED_CARD,
        TABLE, WIDE_TABLE, CLOZE, ARABIC_RTL, ARABIC_NO_DIR, MIXED_ARABIC_ENGLISH,
        LONG_QUESTION, LONG_ANSWER, UNICODE_SYMBOLS, EMOJI,
        JS_ON_LOAD, JS_ON_CLICK, JS_ANKIDROID_API, LINKS, FULL_DOCUMENT,
        VISUAL_ONLY, TEXT_ONLY, ANSWER_WITHOUT_CONTENT,
        IMAGE_REF, AUDIO_TAG, MATH_MARKUP, CUSTOM_FONT
    )

    /** The fixtures GATE 08 claims to render faithfully (the `PASS` rows of the matrix). */
    val gate08Verified: List<Fixture> get() = all.filter { it.expectation == Expectation.PASS }

    /** The fixtures whose full behaviour belongs to GATE 09 (media, math, fonts). */
    val deferredToGate09: List<Fixture> get() = all.filter { it.expectation == Expectation.DEFERRED_GATE_09 }

    fun byId(id: String): Fixture? = all.firstOrNull { it.id == id }

    // ------------------------------------------------------------------ builders

    private fun fixture(
        id: String,
        label: String,
        expectation: Expectation,
        questionHtml: String?,
        answerHtml: String?,
        questionText: String?,
        answerText: String?,
        pureAnswerText: String?,
        media: List<AnkiMediaRef> = emptyList(),
        degradations: List<String> = emptyList()
    ): Fixture {
        val noteRef = AnkiNoteRef(backend, "note-$id", collectionKey)
        val card = AnkiRenderedCard(
            ref = AnkiCardRef(backend, cardId = "card-$id", noteId = noteRef.noteId, cardOrd = 0, collectionKey = collectionKey),
            questionHtml = questionHtml,
            answerHtml = answerHtml,
            questionText = questionText,
            answerText = answerText,
            pureAnswerText = pureAnswerText,
            media = media,
            metadata = AnkiCardMetadata(
                deckName = "Rendering::Compatibility",
                noteTypeName = "GATE 08 fixture",
                templateName = "Card 1"
            ),
            noteRef = noteRef,
            deckRef = deckRef,
            degradations = degradations
        )
        return Fixture(id = id, label = label, expectation = expectation, card = card)
    }

    private fun wideTableHtml(): String = buildString(4096) {
        append("<table border=\"1\" cellpadding=\"4\" style=\"white-space:nowrap\">\n<thead><tr>")
        for (column in 1..12) append("<th>Column ").append(column).append("</th>")
        append("</tr></thead>\n<tbody>\n")
        for (row in 1..8) {
            append("<tr>")
            for (column in 1..12) {
                append("<td>value-").append(row).append('-').append(column)
                    .append(" with a deliberately long cell payload</td>")
            }
            append("</tr>\n")
        }
        append("</tbody></table>")
    }

    private fun longTextHtml(prefix: String, paragraphs: Int): String = buildString(8192) {
        for (index in 1..paragraphs) {
            append("<p>").append(prefix).append(' ').append(index)
                .append(": a deliberately long paragraph so the card needs to scroll, ")
                .append("repeated enough times to exceed a phone screen several times over. ")
                .append("Clinical detail follows: the patient is stable, afebrile, and the ")
                .append("examination is unremarkable apart from the finding in question.</p>\n")
        }
    }
}
