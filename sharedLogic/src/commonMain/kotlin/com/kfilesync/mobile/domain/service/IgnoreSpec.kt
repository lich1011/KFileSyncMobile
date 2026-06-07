package com.kfilesync.mobile.domain.service

/**
 * `.syncignore` parser + matcher (design doc §14 Phase 3 T3.4).
 *
 * Wire-compatible with the desktop client's `.syncignore` syntax - same
 * patterns, same precedence rules:
 *
 * - Lines starting with '#' are comments (ignored).
 * - Blank lines are ignored.
 * - Lines starting with '!' are *negations* (re-include a previously
 * ignored path). Negations apply only to files; they cannot un-ignore
 * a directory once its parent was ignored (matches gitignore semantics).
 * - Glob patterns use `*` (any chars except `/`), `**` (any chars
 * including `/`), `?` (single char), `[abc]` (char class).
 * - A trailing `/` matches directories only.
 * - Leading `/` anchors to the share root; otherwise the pattern may
 * match at any depth.
 *
 * On top of the user's `.syncignore` file, mobile applies an extra layer
 * of **default ignores** for platform-specific cruft that should never
 * leave the device - see [DEFAULT_MOBILE_IGNORES].
 *
 * This module is pure (no I/O); the file reader lives in Phase 4 alongside
 * the file-watcher infrastructure.
 */
class IgnoreSpec(
    private val rules: List<Rule>
) {

    /**
     * 'true' iff [path] (relative to the share root, forward-slash separated)
     * should be skipped during indexing/sync.
     */
    fun shouldIgnore(path: String, isDirectory: Boolean = false): Boolean {
        // Walk rules in order; later rules override earlier ones. This matches
        // gitignore semantics where a '!foo' after 'foo' re-includes it.
        var ignored = false
        for (rule in rules) {
            if (rule.directoryOnly && !isDirectory) continue
            if (rule.matches(path)) {
                ignored = !rule.negate
            }
        }
        return ignored
    }

    data class Rule(
        val pattern: String,
        val negate: Boolean,
        val anchored: Boolean,
        val directoryOnly: Boolean,
        val regex: Regex
    ) {

        fun matches(path: String): Boolean {
            // Anchored patterns match from the start of the path. Unanchored
            // patterns may match at any path segment - gitignore semantics.
            if (anchored) return regex.matches(path)

            // Try the full path, then strip leading segments until empty.
            var rest: String? = path
            while (rest != null) {
                if (regex.matches(rest)) return true
                val nextSlash = rest.indexOf('/')
                rest = if (nextSlash < 0) null else rest.substring(nextSlash + 1)
            }
            return false
        }
    }

    companion object {
        /**
         * Mobile-specific default ignores (design doc §14 Phase 3 T3.4).
         *
         * Desktop equivalent omits these because they don't appear on
         * non-mobile filesystems. Order matters only for '!'-negations,
         * none of which we use here.
         */
        val DEFAULT_MOBILE_IGNORES: List<String> = listOf(
            // System detritus.
            ".DS_Store",
            "Trashes",
            "Trash-*",
            ".Spotlight-V100",
            ".fseventsd",
            "Thumbs.db",
            "ehthumbs.db",
            "desktop.ini",

            // Mobile build / install artefacts that occasionally land in
            // shared folders when developers point a share at an SDK dir.
            "*.apk",
            "*.aab",
            "*.ipa",
            "*.dSYM",
            "*.xcarchive",
            "*.xcuserstate",
            "build/",
            ".gradle/",
            "DerivedData/",
            ".idea/",
            "node_modules/",
            ".cxx/",

            // Lock / temp / partial files.
            "*.tmp",
            "*.part",
            "*.crdownload",
            "~$*",
            ".~lock.*#",

            // Photo-app caches that surface inside SAF-shared dirs.
            ".thumbnails/",
            ".Trash/",

            // Editor swap files.
            "*.swp",
            "*.swo",
            ".vscode/",
            ".cursor/"
        )

        /**
         * Parse a `.syncignore` file's text content into an [IgnoreSpec].
         * Newlines '\n' or '\r\n' both work. Empty / comment lines are skipped.
         */
        fun parse(text: String): IgnoreSpec {
            val rules = text.lineSequence()
                .mapNotNull { parseLine(it) }
                .toList()
            return IgnoreSpec(rules)
        }

        /** Compose user rules + the mobile defaults. Defaults run *before* user rules, so a user
         * `!build/` would re-include it (gitignore precedence). */
        fun withMobileDefaults(userText: String = ""): IgnoreSpec {
            val defaultRules = DEFAULT_MOBILE_IGNORES.mapNotNull { parseLine(it) }
            val userRules = userText.lineSequence().mapNotNull { parseLine(it) }.toList()
            return IgnoreSpec(defaultRules + userRules)
        }

        private fun parseLine(rawLine: String): Rule? {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
            var pattern = trimmed
            val negate = pattern.startsWith("!").also { if (it) pattern = pattern.drop(1) }
            val anchored = pattern.startsWith("/").also { if (it) pattern = pattern.drop(1) }
            val directoryOnly = pattern.endsWith("/").also { if (it) pattern = pattern.dropLast(1) }
            if (pattern.isEmpty()) return null
            return Rule(
                pattern = pattern,
                negate = negate,
                anchored = anchored,
                directoryOnly = directoryOnly,
                regex = compileGlob(pattern)
            )
        }

        /**
         * Compile a gitignore-flavoured glob into a [Regex]. Behaviour notes:
         * - `*`   → `[^/]*`       (any chars except `/`)
         * - `**`  → `.*`          (any chars including `/`)
         * - `?`   → `[^/]`        (single non-slash char)
         * - `[...]` → preserved as-is
         * - other meta chars → escaped
         */
        private fun compileGlob(glob: String): Regex {
            val sb = StringBuilder()
            sb.append("^")
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when (c) {
                    '*' -> {
                        if (i + 1 < glob.length && glob[i + 1] == '*') {
                            sb.append(".*")
                            i += 1
                        } else {
                            sb.append("[^/]*")
                        }
                    }
                    '?' -> sb.append("[^/]")
                    '[' -> {
                        val end = glob.indexOf(']', i + 1)
                        if (end < 0) sb.append(Regex.escape("[")) else {
                            sb.append(glob.substring(i, end + 1))
                            i = end
                        }
                    }
                    '.', '(', ')', '+', '|', '^', '$', '{', '}', '\\' -> sb.append('\\').append(c)
                    else -> sb.append(c)
                }
                i += 1
            }
            sb.append("$")
            return Regex(sb.toString())
        }
    }
}