package com.kfilesync.mobile.domain.service

/**
 * iOS actual for [IgnoreSpec], matching KFileSyncCore's `COMMON_DEFAULTS` +
 * `MOBILE_DEFAULTS` + `.syncignore` gitignore-style rules.
 */
actual class IgnoreSpec private constructor(private val rules: List<Rule>) {

    private data class Rule(
        val negated: Boolean,
        val dirOnly: Boolean,
        val anchored: Boolean,
        val hasSlash: Boolean,
        val regex: Regex
    )

    actual fun shouldIgnore(path: String, isDirectory: Boolean): Boolean {
        val normalized = path.replace('\\', '/').trimStart('/')
        if (normalized.isEmpty()) return false

        // Check parent directory prefixes first: if any ancestor dir is ignored,
        // gitignore semantics prevent negations from un-ignoring children.
        val parts = normalized.split('/')
        for (i in 1 until parts.size) {
            val parentDir = parts.subList(0, i).joinToString("/")
            if (evaluatePath(parentDir, isDirectory = true)) {
                return true
            }
        }
        return evaluatePath(normalized, isDirectory)
    }

    private fun evaluatePath(normalized: String, isDirectory: Boolean): Boolean {
        var ignored = false
        val leaf = normalized.substringAfterLast('/')
        for (rule in rules) {
            if (rule.dirOnly && !isDirectory) continue
            val target = if (rule.anchored || rule.hasSlash) normalized else leaf
            if (rule.regex.matches(target)) {
                ignored = !rule.negated
            }
        }
        return ignored
    }

    actual companion object {
        private val COMMON_AND_MOBILE_DEFAULTS = listOf(
            ".lansync-tmp/",
            ".DS_Store",
            "Thumbs.db",
            "desktop.ini",
            "*.tmp",
            "*.swp",
            "*.bak",
            "*~",
            ".Trash/",
            ".Trashes/",
            ".Spotlight-V100/",
            ".fseventsd/",
            ".thumbnails/",
            ".nomedia",
            ".trashed-*",
            "*.pending-*"
        )

        actual fun withMobileDefaults(userText: String): IgnoreSpec {
            val lines = COMMON_AND_MOBILE_DEFAULTS + userText.lineSequence().toList()
            val compiled = lines.mapNotNull { parseRule(it) }
            return IgnoreSpec(compiled)
        }

        private fun parseRule(rawLine: String): Rule? {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
            val negated = trimmed.startsWith("!")
            var pat = if (negated) trimmed.substring(1) else trimmed
            if (pat.isEmpty()) return null
            val dirOnly = pat.endsWith("/")
            if (dirOnly) pat = pat.dropLast(1)
            val anchored = pat.startsWith("/")
            if (anchored) pat = pat.substring(1)
            if (pat.isEmpty()) return null
            val hasSlash = pat.contains('/')
            return Rule(
                negated = negated,
                dirOnly = dirOnly,
                anchored = anchored,
                hasSlash = hasSlash,
                regex = globToRegex(pat)
            )
        }

        private fun globToRegex(glob: String): Regex {
            val sb = StringBuilder("^")
            var i = 0
            while (i < glob.length) {
                when {
                    glob.startsWith("**/", i) -> {
                        sb.append("(?:.*/)?")
                        i += 3
                    }
                    glob.startsWith("**", i) -> {
                        sb.append(".*")
                        i += 2
                    }
                    glob[i] == '*' -> {
                        sb.append("[^/]*")
                        i += 1
                    }
                    glob[i] == '?' -> {
                        sb.append("[^/]")
                        i += 1
                    }
                    glob[i] in ".+^$()|{}\\" -> {
                        sb.append('\\').append(glob[i])
                        i += 1
                    }
                    else -> {
                        sb.append(glob[i])
                        i += 1
                    }
                }
            }
            sb.append('$')
            return Regex(sb.toString())
        }
    }
}
