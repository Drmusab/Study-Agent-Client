package com.studyagent.client.core.anki

/**
 * GATE 05 — deterministic, locale-independent deck ordering (INV-ANKI-DECK-09).
 *
 * Decks are compared segment by segment along their `::` path, so a parent always sorts
 * immediately before its descendants and siblings stay grouped. Each segment is compared
 * case-insensitively first (so `anatomy` and `Zoology` are not separated by case), then by exact
 * code units (so `a` and `A` keep a stable relative order without ever being *merged* — they are
 * two decks), and two decks with identical paths (a possible but unusual duplicate name) are
 * ordered by their backend id, numerically when both ids are numeric. Nothing here depends on
 * the device locale, so the same collection produces the same order on every refresh and device.
 */
object AnkiDeckOrder : Comparator<AnkiDeck> {

    override fun compare(a: AnkiDeck, b: AnkiDeck): Int {
        val pa = a.path
        val pb = b.path
        val shared = minOf(pa.size, pb.size)
        for (i in 0 until shared) {
            val c = compareSegments(pa[i], pb[i])
            if (c != 0) return c
        }
        if (pa.size != pb.size) return pa.size.compareTo(pb.size)
        return compareIds(a.ref.deckId, b.ref.deckId)
    }

    fun compareSegments(x: String, y: String): Int {
        val insensitive = x.compareTo(y, ignoreCase = true)
        return if (insensitive != 0) insensitive else x.compareTo(y)
    }

    fun compareIds(x: String, y: String): Int {
        val lx = x.toLongOrNull()
        val ly = y.toLongOrNull()
        return if (lx != null && ly != null) lx.compareTo(ly) else x.compareTo(y)
    }
}

/**
 * One node of the backend-neutral deck hierarchy (GATE 05 §12/§23).
 *
 * Two kinds exist and they are never confused (INV-ANKI-DECK-04):
 * - [Deck] wraps a real backend deck and therefore carries an [AnkiDeckRef];
 * - [VirtualGroup] is a `::` prefix that the backend did **not** report as a deck of its own. It
 *   has a display path and children but no identity, no counts and no filtered flag, because
 *   inventing any of those would be fabricating backend data.
 *
 * There is no synthetic root: a hierarchy is a *forest* ([AnkiDeckTree.roots]).
 */
sealed interface AnkiDeckTreeNode {
    /** Full `::`-joined path exactly as the backend reported it (or as derived for a group). */
    val fullName: String

    /** Path segments; the original name is never normalized, trimmed or case-folded. */
    val segments: List<String>

    /** Direct children in deterministic order ([AnkiDeckOrder]). */
    val children: List<AnkiDeckTreeNode>

    /** Last path segment — the label a tree UI shows next to the indentation. */
    val name: String get() = segments.last()

    /** Zero-based nesting depth (`0` = top level). */
    val depth: Int get() = segments.size - 1

    data class Deck(
        val deck: AnkiDeck,
        override val children: List<AnkiDeckTreeNode> = emptyList()
    ) : AnkiDeckTreeNode {
        override val fullName: String get() = deck.name
        override val segments: List<String> get() = deck.path
        val ref: AnkiDeckRef get() = deck.ref
    }

    data class VirtualGroup(
        override val segments: List<String>,
        override val children: List<AnkiDeckTreeNode>
    ) : AnkiDeckTreeNode {
        init {
            require(segments.isNotEmpty())
            require(children.isNotEmpty()) { "A virtual group exists only to hold real decks" }
        }
        override val fullName: String get() = segments.joinToString(AnkiDeckTreeBuilder.SEPARATOR)
    }
}

/**
 * Immutable deck hierarchy built from one deck list. [deckCount] counts real decks only;
 * [virtualGroupCount] counts derived prefixes; [maxDepth] is the deepest zero-based node depth
 * (`0` for a flat list and for an empty tree).
 */
data class AnkiDeckTree(
    val roots: List<AnkiDeckTreeNode>,
    val deckCount: Int,
    val virtualGroupCount: Int,
    val maxDepth: Int
) {
    init {
        require(deckCount >= 0 && virtualGroupCount >= 0 && maxDepth >= 0)
    }

    val isEmpty: Boolean get() = roots.isEmpty()

    /**
     * Pre-order traversal — every parent before its children, siblings in [AnkiDeckOrder] — which
     * is the flat list an indented deck UI renders. Iterative, O(nodes).
     */
    fun flatten(): List<AnkiDeckTreeNode> {
        if (roots.isEmpty()) return emptyList()
        val out = ArrayList<AnkiDeckTreeNode>(deckCount + virtualGroupCount)
        val stack = ArrayDeque<AnkiDeckTreeNode>()
        for (i in roots.indices.reversed()) stack.addLast(roots[i])
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            out.add(node)
            val children = node.children
            for (i in children.indices.reversed()) stack.addLast(children[i])
        }
        return out
    }

    /** Real decks in pre-order (the display order), without the virtual groups. */
    fun decksInDisplayOrder(): List<AnkiDeck> =
        flatten().mapNotNull { (it as? AnkiDeckTreeNode.Deck)?.deck }

    companion object {
        val EMPTY = AnkiDeckTree(roots = emptyList(), deckCount = 0, virtualGroupCount = 0, maxDepth = 0)
    }
}

/**
 * GATE 05 — builds an [AnkiDeckTree] from a flat deck list in O(n log n) (INV-ANKI-DECK-05).
 *
 * Algorithm:
 * 1. Sort with [AnkiDeckOrder]. Because a prefix sorts before its extensions, every real parent
 *    is processed before any of its descendants.
 * 2. Index real decks by exact full name. When two decks share a full name (distinct ids), the
 *    first in sort order (lowest id) is the *anchor*: descendants attach to it and the other
 *    duplicate becomes a sibling leaf. Deterministic, nothing merged, nothing dropped.
 * 3. Walk the sorted decks; for each, make sure its parent path exists — a real deck if the
 *    backend reported one, otherwise a [AnkiDeckTreeNode.VirtualGroup] created on first need —
 *    and attach the deck. Attach order is sort order, so sibling order is [AnkiDeckOrder] without
 *    a second sort.
 *
 * Names are used verbatim: no trimming, no case folding, no Unicode normalization. `A::B` and
 * `a::b` are two hierarchies; ` A` and `A` are two decks. Only the backend id is identity.
 */
/**
 * Local in-memory deck-name matching (GATE 05 §76/§77). Not a search UI and not an Anki query.
 *
 * Matches [AnkiDeck.name] (full path), [AnkiDeck.leafName] and every path segment. Comparison
 * uses Unicode case-folding (`ignoreCase = true`) rather than a device locale, so the same query
 * yields the same hits on every device. Blank queries match everything. Identity is never used
 * as a search key — callers that already have an [AnkiDeckRef] should look up by ref instead.
 */
object AnkiDeckFilter {
    fun matches(deck: AnkiDeck, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        if (deck.name.contains(needle, ignoreCase = true)) return true
        if (deck.leafName.contains(needle, ignoreCase = true)) return true
        return deck.path.any { it.contains(needle, ignoreCase = true) }
    }

    fun filter(decks: List<AnkiDeck>, query: String): List<AnkiDeck> =
        if (query.isBlank()) decks else decks.filter { matches(it, query) }
}

object AnkiDeckTreeBuilder {

    const val SEPARATOR: String = "::"

    private class MutableNode(val deck: AnkiDeck?, val segments: List<String>) {
        val children = ArrayList<MutableNode>()

        fun freeze(): AnkiDeckTreeNode {
            val frozen: List<AnkiDeckTreeNode> = if (children.isEmpty()) emptyList() else children.map { it.freeze() }
            val real = deck
            return if (real != null) AnkiDeckTreeNode.Deck(real, frozen)
            else AnkiDeckTreeNode.VirtualGroup(segments, frozen)
        }
    }

    fun build(decks: List<AnkiDeck>): AnkiDeckTree {
        if (decks.isEmpty()) return AnkiDeckTree.EMPTY
        val sorted = decks.sortedWith(AnkiDeckOrder)

        val byPath = HashMap<String, MutableNode>(sorted.size * 2)
        for (deck in sorted) {
            if (!byPath.containsKey(deck.name)) byPath[deck.name] = MutableNode(deck, deck.path)
        }

        val roots = ArrayList<MutableNode>()
        var virtualGroups = 0
        var maxDepth = 0

        fun ensureParent(segments: List<String>): MutableNode? {
            if (segments.size <= 1) return null
            val parentSegments = segments.subList(0, segments.size - 1)
            val key = parentSegments.joinToString(SEPARATOR)
            byPath[key]?.let { return it }
            val grandParent = ensureParent(parentSegments)
            val group = MutableNode(deck = null, segments = ArrayList(parentSegments))
            byPath[key] = group
            virtualGroups += 1
            if (grandParent == null) roots.add(group) else grandParent.children.add(group)
            return group
        }

        for (deck in sorted) {
            val anchor = byPath.getValue(deck.name)
            val node = if (anchor.deck?.ref == deck.ref) anchor else MutableNode(deck, deck.path)
            val parent = ensureParent(deck.path)
            if (parent == null) roots.add(node) else parent.children.add(node)
            val depth = deck.path.size - 1
            if (depth > maxDepth) maxDepth = depth
        }

        return AnkiDeckTree(
            roots = roots.map { it.freeze() },
            deckCount = sorted.size,
            virtualGroupCount = virtualGroups,
            maxDepth = maxDepth
        )
    }
}
