package com.maxrave.domain.utils

object UpdatePolicy {
    const val CHECK_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L

    fun isCheckDue(nowMillis: Long, lastCheckMillis: Long): Boolean =
        lastCheckMillis <= 0L || nowMillis < lastCheckMillis || nowMillis - lastCheckMillis >= CHECK_INTERVAL_MILLIS

    /** Compare version numbers rather than tags or strings (for example, 2.10 > 2.9). */
    fun isNewerRelease(releaseTag: String, installedVersion: String): Boolean {
        val release = Version.parse(releaseTag) ?: return false
        val installed = Version.parse(installedVersion) ?: return false
        return release > installed
    }

    private data class Version(
        val numbers: List<Long>,
        val prerelease: List<String>,
    ) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            repeat(maxOf(numbers.size, other.numbers.size)) { index ->
                val result = (numbers.getOrNull(index) ?: 0L).compareTo(other.numbers.getOrNull(index) ?: 0L)
                if (result != 0) return result
            }
            if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
                return when {
                    prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
                    prerelease.isEmpty() -> 1
                    else -> -1
                }
            }
            repeat(minOf(prerelease.size, other.prerelease.size)) { index ->
                val left = prerelease[index]
                val right = other.prerelease[index]
                val leftNumber = left.toLongOrNull()
                val rightNumber = right.toLongOrNull()
                val result = when {
                    leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                    leftNumber != null -> -1
                    rightNumber != null -> 1
                    else -> left.compareTo(right)
                }
                if (result != 0) return result
            }
            return prerelease.size.compareTo(other.prerelease.size)
        }

        companion object {
            private val pattern = Regex("^[vV]?(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")

            fun parse(value: String): Version? {
                val match = pattern.matchEntire(value.trim()) ?: return null
                val numbers = match.groupValues[1].split('.').map { it.toLongOrNull() ?: return null }
                val prerelease = match.groupValues[2].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
                if (prerelease.any { it.isEmpty() }) return null
                return Version(numbers, prerelease)
            }
        }
    }
}
