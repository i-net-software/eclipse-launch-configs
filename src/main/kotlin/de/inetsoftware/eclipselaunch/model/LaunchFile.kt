package de.inetsoftware.eclipselaunch.model

/**
 * An Eclipse `.launch` file that can be changed without losing anything else.
 *
 * The file is kept as text. Every change replaces only the bytes of the affected attribute element, so unknown
 * attributes, their order, indentation, line separators and entity spelling stay exactly as Eclipse wrote them.
 * New attributes are inserted at the position Eclipse would use (Eclipse writes attributes sorted by key).
 */
class LaunchFile(text: String) {

    var text: String = text
        private set

    private var parsed: Parsed = parse(text)

    /** The launch configuration type, e.g. `org.eclipse.jdt.junit.launchconfig`. */
    val type: String get() = parsed.type

    /** All attribute keys in file order. */
    val keys: List<String> get() = parsed.attributes.map { it.key }

    fun has(key: String): Boolean = find(key) != null

    fun getString(key: String): String? = find(key)?.takeIf { it.kind != Kind.LIST && it.kind != Kind.MAP }?.value

    fun getBoolean(key: String): Boolean? = getString(key)?.toBooleanStrictOrNull()

    fun getList(key: String): List<String>? = find(key)?.takeIf { it.kind == Kind.LIST }?.list

    fun getMap(key: String): Map<String, String>? = find(key)?.takeIf { it.kind == Kind.MAP }?.map

    /** Sets a string attribute; `null` removes it. */
    fun setString(key: String, value: String?) = setScalar(key, value, Kind.STRING)

    /** Sets a boolean attribute; `null` removes it. */
    fun setBoolean(key: String, value: Boolean?) = setScalar(key, value?.toString(), Kind.BOOLEAN)

    /** Sets a list attribute; `null` removes it. */
    fun setList(key: String, value: List<String>?) {
        val old = find(key)
        if (value == null) return remove(old)
        if (old?.kind == Kind.LIST && old.list == value) return
        replaceOrInsert(old, key) { indent, unit, nl ->
            buildString {
                append("<listAttribute key=\"").append(escape(key)).append("\">")
                for (entry in value) append(nl).append(indent).append(unit).append("<listEntry value=\"").append(escape(entry)).append("\"/>")
                append(nl).append(indent).append("</listAttribute>")
            }
        }
    }

    /** Sets a map attribute; `null` removes it. Existing keys keep their order, new keys are appended. */
    fun setMap(key: String, value: Map<String, String>?) {
        val old = find(key)
        if (value == null) return remove(old)
        val ordered = LinkedHashMap<String, String>()
        old?.map?.keys?.forEach { k -> value[k]?.let { ordered[k] = it } }
        value.forEach { (k, v) -> ordered.putIfAbsent(k, v) }
        if (old?.kind == Kind.MAP && old.map?.toList() == ordered.toList()) return
        replaceOrInsert(old, key) { indent, unit, nl ->
            buildString {
                append("<mapAttribute key=\"").append(escape(key)).append("\">")
                for ((k, v) in ordered) {
                    append(nl).append(indent).append(unit)
                    append("<mapEntry key=\"").append(escape(k)).append("\" value=\"").append(escape(v)).append("\"/>")
                }
                append(nl).append(indent).append("</mapAttribute>")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private fun find(key: String) = parsed.attributes.firstOrNull { it.key == key }

    private fun setScalar(key: String, value: String?, kind: Kind) {
        val old = find(key)
        if (value == null) return remove(old)
        if (old != null && old.kind == kind && old.valueRange != null) {
            if (old.value == value) return
            // Replace only the raw attribute value, everything around it stays untouched
            edit(old.valueRange.first, old.valueRange.last + 1, escape(value))
            return
        }
        replaceOrInsert(old, key) { _, _, _ -> "<${kind.tag} key=\"${escape(key)}\" value=\"${escape(value)}\"/>" }
    }

    private fun remove(old: Attribute?) {
        old ?: return
        // Remove the whole line including its indentation and line separator
        var start = old.start
        while (start > 0 && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
        var end = old.end
        if (start == 0 || text[start - 1] == '\n') {
            if (end < text.length && text[end] == '\r') end++
            if (end < text.length && text[end] == '\n') end++
        }
        edit(start, end, "")
    }

    private fun replaceOrInsert(old: Attribute?, key: String, render: (indent: String, unit: String, nl: String) -> String) {
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        if (old != null) {
            edit(old.start, old.end, render(indentOf(old.start), parsed.indentUnit, nl))
            return
        }
        val next = parsed.attributes.firstOrNull { it.key > key }
        if (next != null) {
            val indent = indentOf(next.start)
            val lineStart = next.start - indent.length
            edit(lineStart, lineStart, indent + render(indent, parsed.indentUnit, nl) + nl)
        } else {
            val last = parsed.attributes.lastOrNull()
            val indent = if (last != null) indentOf(last.start) else parsed.indentUnit
            val pos = last?.end ?: parsed.rootContentStart
            edit(pos, pos, nl + indent + render(indent, parsed.indentUnit, nl))
        }
    }

    private fun indentOf(pos: Int): String {
        var i = pos
        while (i > 0 && (text[i - 1] == ' ' || text[i - 1] == '\t')) i--
        return text.substring(i, pos)
    }

    private fun edit(start: Int, end: Int, replacement: String) {
        text = text.substring(0, start) + replacement + text.substring(end)
        parsed = parse(text)
    }

    enum class Kind(val tag: String) {
        STRING("stringAttribute"), BOOLEAN("booleanAttribute"), INT("intAttribute"), LIST("listAttribute"),
        SET("setAttribute"), MAP("mapAttribute"), OTHER("");

        companion object {
            fun of(tag: String) = entries.firstOrNull { it.tag == tag } ?: OTHER
        }
    }

    private class Attribute(
        val key: String,
        val kind: Kind,
        val start: Int,
        val end: Int,
        val value: String?,
        /** Range of the raw text inside the quotes of `value="…"` */
        val valueRange: IntRange?,
        val list: List<String>?,
        val map: Map<String, String>?,
    )

    private class Parsed(val type: String, val attributes: List<Attribute>, val indentUnit: String, val rootContentStart: Int)

    companion object {
        /** Escapes a value the way Eclipse's XML writer does. */
        fun escape(value: String): String = buildString(value.length) {
            for (c in value) when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\n' -> append("&#10;")
                '\r' -> append("&#13;")
                '\t' -> append("&#9;")
                else -> append(c)
            }
        }

        fun unescape(raw: String): String {
            if (raw.indexOf('&') < 0) return raw
            val sb = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                val semi = if (c == '&') raw.indexOf(';', i) else -1
                if (semi < 0) {
                    sb.append(c); i++; continue
                }
                val entity = raw.substring(i + 1, semi)
                val decoded = when {
                    entity == "amp" -> "&"
                    entity == "lt" -> "<"
                    entity == "gt" -> ">"
                    entity == "quot" -> "\""
                    entity == "apos" -> "'"
                    entity.startsWith("#x") || entity.startsWith("#X") -> entity.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                    entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                    else -> null
                }
                if (decoded == null) {
                    sb.append(c); i++
                } else {
                    sb.append(decoded); i = semi + 1
                }
            }
            return sb.toString()
        }

        private fun parse(text: String): Parsed {
            val scanner = Scanner(text)
            var type: String? = null
            var rootContentStart = -1
            val attributes = ArrayList<Attribute>()
            var depth = 0
            // State of the current top-level child element
            var current: Tag? = null
            var currentStart = 0
            val entries = ArrayList<Tag>()
            var indentUnit: String? = null

            while (true) {
                val tag = scanner.next() ?: break
                when (tag.kind) {
                    TagKind.OPEN, TagKind.EMPTY -> {
                        if (depth == 0) {
                            if (tag.name != "launchConfiguration") throw LaunchFileException("Not an Eclipse launch configuration: <${tag.name}>")
                            type = tag.attrs["type"]?.value ?: throw LaunchFileException("Missing launch configuration type")
                            rootContentStart = tag.end
                        } else if (depth == 1) {
                            current = tag
                            currentStart = tag.start
                            entries.clear()
                            if (indentUnit == null) {
                                var i = tag.start
                                while (i > 0 && (text[i - 1] == ' ' || text[i - 1] == '\t')) i--
                                indentUnit = text.substring(i, tag.start).ifEmpty { null }
                            }
                        } else if (depth == 2) {
                            entries.add(tag)
                        }
                        if (tag.kind == TagKind.OPEN) depth++
                        if (depth == 1 && tag.kind == TagKind.EMPTY && current != null) {
                            attributes.add(toAttribute(current, currentStart, tag.end, entries))
                            current = null
                        }
                    }
                    TagKind.CLOSE -> {
                        depth--
                        if (depth == 1 && current != null) {
                            attributes.add(toAttribute(current, currentStart, tag.end, entries))
                            current = null
                        }
                    }
                }
            }
            if (type == null) throw LaunchFileException("Missing <launchConfiguration> element")
            return Parsed(type, attributes, indentUnit ?: "    ", rootContentStart)
        }

        private fun toAttribute(tag: Tag, start: Int, end: Int, children: List<Tag>): Attribute {
            val kind = Kind.of(tag.name)
            val key = tag.attrs["key"]?.value ?: ""
            val value = tag.attrs["value"]
            return Attribute(
                key = key,
                kind = kind,
                start = start,
                end = end,
                value = value?.value,
                valueRange = value?.range,
                list = if (kind == Kind.LIST || kind == Kind.SET) children.filter { it.name == "listEntry" || it.name == "setEntry" }.map { it.attrs["value"]?.value ?: "" } else null,
                map = if (kind == Kind.MAP) children.filter { it.name == "mapEntry" }.associateTo(LinkedHashMap()) { (it.attrs["key"]?.value ?: "") to (it.attrs["value"]?.value ?: "") } else null,
            )
        }
    }

    private enum class TagKind { OPEN, CLOSE, EMPTY }

    private class AttrValue(val value: String, val range: IntRange)

    private class Tag(val kind: TagKind, val name: String, val start: Int, val end: Int, val attrs: Map<String, AttrValue>)

    /** Minimal XML scanner: element tags with their source offsets; skips declarations, comments, CDATA and text. */
    private class Scanner(private val s: String) {
        private var pos = 0

        fun next(): Tag? {
            while (true) {
                val lt = s.indexOf('<', pos)
                if (lt < 0) return null
                when {
                    s.startsWith("<?", lt) -> pos = skipTo(lt, "?>")
                    s.startsWith("<!--", lt) -> pos = skipTo(lt, "-->")
                    s.startsWith("<![CDATA[", lt) -> pos = skipTo(lt, "]]>")
                    s.startsWith("<!", lt) -> pos = skipTo(lt, ">")
                    s.startsWith("</", lt) -> {
                        val gt = s.indexOf('>', lt)
                        if (gt < 0) throw LaunchFileException("Unterminated end tag at $lt")
                        pos = gt + 1
                        return Tag(TagKind.CLOSE, s.substring(lt + 2, gt).trim(), lt, pos, emptyMap())
                    }
                    else -> return element(lt)
                }
            }
        }

        private fun skipTo(from: Int, end: String): Int {
            val i = s.indexOf(end, from)
            if (i < 0) throw LaunchFileException("Unterminated markup at $from")
            return i + end.length
        }

        private fun element(lt: Int): Tag {
            var i = lt + 1
            while (i < s.length && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/') i++
            val name = s.substring(lt + 1, i)
            val attrs = LinkedHashMap<String, AttrValue>()
            while (true) {
                while (i < s.length && s[i].isWhitespace()) i++
                if (i >= s.length) throw LaunchFileException("Unterminated tag <$name>")
                if (s[i] == '>') {
                    pos = i + 1
                    return Tag(TagKind.OPEN, name, lt, pos, attrs)
                }
                if (s.startsWith("/>", i)) {
                    pos = i + 2
                    return Tag(TagKind.EMPTY, name, lt, pos, attrs)
                }
                val eq = s.indexOf('=', i)
                if (eq < 0) throw LaunchFileException("Malformed attribute in <$name>")
                val attrName = s.substring(i, eq).trim()
                var q = eq + 1
                while (q < s.length && s[q].isWhitespace()) q++
                val quote = s[q]
                if (quote != '"' && quote != '\'') throw LaunchFileException("Unquoted attribute $attrName in <$name>")
                val close = s.indexOf(quote, q + 1)
                if (close < 0) throw LaunchFileException("Unterminated attribute $attrName in <$name>")
                attrs[attrName] = AttrValue(unescape(s.substring(q + 1, close)), (q + 1) until close)
                i = close + 1
            }
        }
    }
}

class LaunchFileException(message: String) : Exception(message)
