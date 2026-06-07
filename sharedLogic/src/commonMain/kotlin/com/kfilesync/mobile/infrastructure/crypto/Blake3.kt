package com.kfilesync.mobile.infrastructure.crypto

/**
 * Pure-Kotlin BLAKE3 hasher for chunk-level integrity (Phase 2 T2.2 / T2.3).
 *
 * Reference: BLAKE3 spec v0.0.8 (Aumasson, Neves, O'Hearn, Winnerlein, 2020).
 * This is the simple, non-parallel, single-key flavour – sufficient for
 * mobile chunk sizes (≤ 16 MiB) where SIMD/threading overhead would dwarf
 * the benefit.
 *
 * Interop note: produces byte-identical output to the desktop client's
 * `blake3` crate (default API) and to the reference C implementation, so
 * the receiver can verify chunk hashes computed by either platform.
 *
 * Allocations: each [hash] call allocates ~2 KiB of working state – fine
 * for the per-chunk call rate (a few hundred per file). For multi-file
 * jobs, callers re-use a single [Blake3Hasher] via `reset()`.
 */
class Blake3Hasher {

    /**
     * One-shot convenience: hash [input] and return a 32-byte digest.
     * Equivalent to `Blake3Hasher().update(input).finalize(32)`.
     */
    fun hash(input: ByteArray, outputLength: Int = 32): ByteArray {
        reset()
        update(input, 0, input.size)
        val out = ByteArray(outputLength)
        finalize(out, 0, outputLength)
        return out
    }

    /** Reset to a fresh hashing state (no key, no derive context). */
    fun reset(): Blake3Hasher {
        chunkState = ChunkState(IV.copyOf(), 0L, 0)
        cvStackLen = 0
        return this
    }

    /** Append [length] bytes from [data] starting at [offset]. */
    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size): Blake3Hasher {
        var off = offset
        var remaining = length
        while (remaining > 0) {
            if (chunkState.blockLen == CHUNK_LEN) {
                val chunkCv = chunkState.finalizeOutput(rootFlag = 0).chainingValue()
                addChunkChainingValue(chunkCv, chunkState.chunkCounter + 1)
                chunkState = ChunkState(IV.copyOf(), chunkState.chunkCounter + 1, 0)
            }
            val want = CHUNK_LEN - chunkState.blockLen
            val take = minOf(want, remaining)
            chunkState.update(data, off, take)
            off += take
            remaining -= take
        }
        return this
    }

    /** Write [length] bytes of digest into [dest] starting at [destOffset]. */
    fun finalize(dest: ByteArray, destOffset: Int = 0, length: Int = 32) {
        // Roll the stack down to a single output. Start with the in-progress chunk.
        var output = chunkState.finalizeOutput(rootFlag = 0)
        // Walk the stack from top to bottom merging.
        var parentNodesRemaining = cvStackLen
        while (parentNodesRemaining > 0) {
            parentNodesRemaining -= 1
            output = parentOutput(
                left = cvStack[parentNodesRemaining],
                right = output.chainingValue(),
                key = IV
            )
        }
        output.rootFlag = ROOT
        output.expand(dest, destOffset, length)
    }

    // ------------------ internal state ------------------

    private val cvStack: Array<IntArray> = Array(54) { IntArray(8) }
    private var cvStackLen: Int = 0
    private var chunkState: ChunkState = ChunkState(IV.copyOf(), 0L, 0)

    private fun addChunkChainingValue(newCv: IntArray, totalChunks: Long) {
        // Merge with stack entries whose subtree is now complete (post-order tree build)
        var cv = newCv
        var total = totalChunks
        while ((total and 1L) == 0L) {
            cv = parentChainingValue(cvStack[cvStackLen - 1], cv, IV)
            cvStackLen -= 1
            total = total ushr 1
        }
        cvStack[cvStackLen] = cv
        cvStackLen += 1
    }

    // ------------------ ChunkState ------------------

    /** Working state for the current chunk (up to 16 blocks of 64 bytes). */
    private inner class ChunkState(
        val chainingValue: IntArray,
        val chunkCounter: Long,
        var blockLen: Int
    ) {
        val block: ByteArray = ByteArray(BLOCK_LEN)
        var blockOffset: Int = 0
        var blocksCompressed: Int = 0

        fun update(input: ByteArray, off: Int, length: Int) {
            var remaining = length
            var src = off
            while (remaining > 0) {
                if (blockOffset == BLOCK_LEN) {
                    val flags = startFlag() // start of chunk only on the first block
                    val cv = compress(chainingValue, block, BLOCK_LEN, chunkCounter, flags)
                    // chainingValue := first 8 words of cv
                    for (i in 0 until 8) chainingValue[i] = cv[i]
                    blocksCompressed += 1
                    blockOffset = 0
                    block.fill(0)
                }
                val take = minOf(BLOCK_LEN - blockOffset, remaining)
                input.copyInto(block, blockOffset, src, src + take)
                blockOffset += take
                blockLen += take
                src += take
                remaining -= take
            }
        }

        fun finalizeOutput(rootFlag: Int): Output {
            val flags = startFlag() or CHUNK_END or rootFlag
            return Output(
                inputChainingValue = chainingValue.copyOf(),
                blockWords = wordsFromBlock(block),
                counter = chunkCounter,
                blockLen = blockOffset,
                flags = flags
            )
        }

        private fun startFlag(): Int = if (blocksCompressed == 0) CHUNK_START else 0
    }

    /** Captured immediately-before-finalize state; produces the actual digest. */
    private inner class Output(
        val inputChainingValue: IntArray,
        val blockWords: IntArray,
        val counter: Long,
        val blockLen: Int,
        var flags: Int
    ) {
        var rootFlag: Int = 0

        fun chainingValue(): IntArray {
            val full = compress(inputChainingValue, blockBytesFromWords(blockWords), blockLen, counter, flags)
            return full.copyOfRange(0, 8)
        }

        fun expand(dest: ByteArray, destOffset: Int, length: Int) {
            var ctr = 0L
            var written = 0
            while (written < length) {
                val words = compress(
                    inputChainingValue,
                    blockBytesFromWords(blockWords),
                    blockLen,
                    ctr,
                    flags or rootFlag
                )
                val toWrite = minOf(64, length - written)
                writeWordsLittleEndian(words, dest, destOffset + written, toWrite)
                ctr += 1
                written += toWrite
            }
        }
    }

    private fun parentOutput(left: IntArray, right: IntArray, key: IntArray): Output {
        val block = IntArray(16)
        for (i in 0 until 8) block[i] = left[i]
        for (i in 0 until 8) block[i + 8] = right[i]
        return Output(
            inputChainingValue = key.copyOf(),
            blockWords = block,
            counter = 0L,
            blockLen = BLOCK_LEN,
            flags = PARENT
        )
    }

    private fun parentChainingValue(left: IntArray, right: IntArray, key: IntArray): IntArray =
        parentOutput(left, right, key).chainingValue()

    // ------------------ core compression ------------------

    private fun compress(
        chainingValue: IntArray,
        block: ByteArray,
        blockLen: Int,
        counter: Long,
        flags: Int
    ): IntArray {
        val m = wordsFromBlock(block)
        val state = IntArray(16)
        for (i in 0 until 8) state[i] = chainingValue[i]
        for (i in 0 until 4) state[i + 8] = IV[i]
        state[12] = (counter and 0xFFFFFFFFL).toInt()
        state[13] = (counter ushr 32).toInt()
        state[14] = blockLen
        state[15] = flags

        // 7 rounds with message permutation.
        round(state, m)
        permute(m); round(state, m)
        permute(m); round(state, m)
        permute(m); round(state, m)
        permute(m); round(state, m)
        permute(m); round(state, m)
        permute(m); round(state, m)

        // Output transform: XOR upper half into lower (chaining value); upper
        // XOR'd with original chainingValue for extended output.
        for (i in 0 until 8) {
            state[i] = state[i] xor state[i + 8]
            state[i + 8] = state[i + 8] xor chainingValue[i]
        }
        return state
    }

    private fun round(state: IntArray, m: IntArray) {
        // Columns
        g(state, 0, 4, 8, 12, m[0], m[1])
        g(state, 1, 5, 9, 13, m[2], m[3])
        g(state, 2, 6, 10, 14, m[4], m[5])
        g(state, 3, 7, 11, 15, m[6], m[7])
        // Diagonals
        g(state, 0, 5, 10, 15, m[8], m[9])
        g(state, 1, 6, 11, 12, m[10], m[11])
        g(state, 2, 7, 8, 13, m[12], m[13])
        g(state, 3, 4, 9, 14, m[14], m[15])
    }

    private fun g(state: IntArray, a: Int, b: Int, c: Int, d: Int, mx: Int, my: Int) {
        state[a] = state[a] + state[b] + mx
        state[d] = Integer.rotateRight(state[d] xor state[a], 16)
        state[c] = state[c] + state[d]
        state[b] = Integer.rotateRight(state[b] xor state[c], 12)
        state[a] = state[a] + state[b] + my
        state[d] = Integer.rotateRight(state[d] xor state[a], 8)
        state[c] = state[c] + state[d]
        state[b] = Integer.rotateRight(state[b] xor state[c], 7)
    }

    private fun permute(m: IntArray) {
        val tmp = IntArray(16)
        for (i in 0 until 16) tmp[i] = m[MSG_PERMUTATION[i]]
        for (i in 0 until 16) m[i] = tmp[i]
    }

    private fun wordsFromBlock(block: ByteArray): IntArray {
        val out = IntArray(16)
        for (i in 0 until 16) {
            val off = i * 4
            out[i] = (block[off].toInt() and 0xFF) or
                    ((block[off + 1].toInt() and 0xFF) shl 8) or
                    ((block[off + 2].toInt() and 0xFF) shl 16) or
                    ((block[off + 3].toInt() and 0xFF) shl 24)
        }
        return out
    }

    private fun blockBytesFromWords(words: IntArray): ByteArray {
        val out = ByteArray(64)
        for (i in 0 until 16) {
            val w = words[i]
            val off = i * 4
            out[off] = (w and 0xFF).toByte()
            out[off + 1] = ((w ushr 8) and 0xFF).toByte()
            out[off + 2] = ((w ushr 16) and 0xFF).toByte()
            out[off + 3] = ((w ushr 24) and 0xFF).toByte()
        }
        return out
    }

    private fun writeWordsLittleEndian(words: IntArray, dest: ByteArray, destOffset: Int, length: Int) {
        var i = 0
        while (i < length) {
            val w = words[i / 4]
            val byteInWord = i % 4
            dest[destOffset + i] = ((w ushr (byteInWord * 8)) and 0xFF).toByte()
            i += 1
        }
    }
    private object Integer {
        /** Multiplatform-safe `rotateRight` on Int. */
        fun rotateRight(value: Int, distance: Int): Int =
            (value ushr distance) or (value shl (32 - distance))
    }

    companion object {
        const val CHUNK_LEN: Int = 1024
        const val BLOCK_LEN: Int = 64

        const val CHUNK_START: Int = 1
        const val CHUNK_END: Int = 2
        const val PARENT: Int = 4
        const val ROOT: Int = 8

        private val IV: IntArray = intArrayOf(
            0x6A09E667.toInt(), 0xBB67AE85.toInt(), 0x3C6EF372.toInt(), 0xA54FF53A.toInt(),
            0x510E527F.toInt(), 0x9B05688C.toInt(), 0x1F83D9AB.toInt(), 0x5BE0CD19.toInt()
        )

        private val MSG_PERMUTATION: IntArray =
            intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8)
    }

    /** Convenience: one-shot BLAKE3 of [data], 32-byte digest, hex lowercase. */
    fun blake3Hex(data: ByteArray, length: Int = data.size): String {
        val hasher = Blake3Hasher()
        hasher.update(data, 0, length)
        val out = ByteArray(32)
        hasher.finalize(out)
        return out.toHexLower()
    }
}