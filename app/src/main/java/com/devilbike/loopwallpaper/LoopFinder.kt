package com.devilbike.loopwallpaper

import java.util.PriorityQueue
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A loop that plays frames [startIdx, endIdx) and then jumps back to [startIdx].
 * Frame [endIdx] is the one that should look like [startIdx].
 */
data class LoopResult(
    val startIdx: Int,
    val endIdx: Int,
    val startUs: Long,
    val endUs: Long,
    /** How big the jump at the seam is, compared with a normal frame-to-frame change. */
    val seamRatio: Float,
) {
    val lengthUs get() = endUs - startUs
}

object LoopFinder {

    private const val COARSE_PAIR_BUDGET = 3_000_000.0
    private const val CANDIDATES = 64
    private val WINDOW_WEIGHTS = floatArrayOf(0.5f, 1f, 2f, 1f, 0.5f)

    private class Candidate(val i: Int, val j: Int, val cost: Float)

    /**
     * Finds the start and end frames inside [rangeStartUs, rangeEndUs] whose loop length is between
     * [minLenUs] and [maxLenUs] and whose end frame best matches the start frame, including the motion
     * around them.
     */
    fun find(t: VideoThumbs, rangeStartUs: Long, rangeEndUs: Long, minLenUs: Long, maxLenUs: Long): LoopResult? {
        val pts = t.ptsUs
        val lo = lowerBound(pts, rangeStartUs, 0, pts.size)
        val hi = lowerBound(pts, rangeEndUs + 1, 0, pts.size) - 1
        if (lo >= pts.size || hi <= lo) return null

        fun jFrom(i: Int) = max(i + 1, lowerBound(pts, pts[i] + minLenUs, lo, hi + 1))
        fun jTo(i: Int) = min(hi, lowerBound(pts, pts[i] + maxLenUs + 1, lo, hi + 1) - 1)

        var pairs = 0L
        for (i in lo..hi) {
            val count = jTo(i) - jFrom(i) + 1
            if (count > 0) pairs += count
        }
        if (pairs == 0L) return null
        val stride = max(1, ceil(sqrt(pairs / COARSE_PAIR_BUDGET)).toInt())

        // Coarse pass: compare 16x9 thumbnails, keep the best candidates.
        val heap = PriorityQueue<Candidate>(CANDIDATES + 1, compareByDescending<Candidate> { it.cost })
        for (i in lo..hi step stride) {
            val j1 = jTo(i)
            var j = jFrom(i)
            while (j <= j1) {
                val cost = meanAbsDiff(t.small[i], t.small[j])
                if (heap.size < CANDIDATES) {
                    heap.add(Candidate(i, j, cost))
                } else if (cost < heap.peek()!!.cost) {
                    heap.poll()
                    heap.add(Candidate(i, j, cost))
                }
                j += stride
            }
        }

        // Fine pass: full thumbnails plus neighbouring frames, around each candidate.
        var bestI = -1
        var bestJ = -1
        var bestCost = Float.MAX_VALUE
        val checked = HashSet<Long>()
        for (c in heap) {
            for (i in max(lo, c.i - stride)..min(hi, c.i + stride)) {
                val j0 = max(jFrom(i), c.j - stride)
                val j1 = min(jTo(i), c.j + stride)
                for (j in j0..j1) {
                    if (!checked.add(i.toLong() * pts.size + j)) continue
                    val cost = windowCost(t, i, j)
                    if (cost < bestCost) {
                        bestCost = cost
                        bestI = i
                        bestJ = j
                    }
                }
            }
        }
        if (bestI < 0) return null

        val seam = meanAbsDiff(t.full[bestI], t.full[bestJ])
        val ratio = seam / typicalStep(t, lo, hi)
        return LoopResult(bestI, bestJ, pts[bestI], pts[bestJ], ratio)
    }

    private fun windowCost(t: VideoThumbs, i: Int, j: Int): Float {
        val last = t.frameCount - 1
        var sum = 0f
        var weights = 0f
        val half = WINDOW_WEIGHTS.size / 2
        for (k in WINDOW_WEIGHTS.indices) {
            val offset = k - half
            val a = (i + offset).coerceIn(0, last)
            val b = (j + offset).coerceIn(0, last)
            sum += WINDOW_WEIGHTS[k] * meanAbsDiff(t.full[a], t.full[b])
            weights += WINDOW_WEIGHTS[k]
        }
        return sum / weights
    }

    /** Median difference between consecutive frames, i.e. what a normal frame step looks like. */
    private fun typicalStep(t: VideoThumbs, lo: Int, hi: Int): Float {
        val count = hi - lo
        val step = max(1, count / 500)
        val diffs = ArrayList<Float>()
        var k = lo
        while (k < hi) {
            diffs.add(meanAbsDiff(t.full[k], t.full[k + 1]))
            k += step
        }
        if (diffs.isEmpty()) return 1f
        diffs.sort()
        return max(0.25f, diffs[diffs.size / 2])
    }

    fun meanAbsDiff(a: ByteArray, b: ByteArray): Float {
        var sum = 0
        for (k in a.indices) {
            val d = (a[k].toInt() and 0xFF) - (b[k].toInt() and 0xFF)
            sum += if (d < 0) -d else d
        }
        return sum.toFloat() / a.size
    }

    /** First index in [from, to) whose value is >= [value], or [to] if none. */
    private fun lowerBound(values: LongArray, value: Long, from: Int, to: Int): Int {
        var low = from
        var high = to
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid] < value) low = mid + 1 else high = mid
        }
        return low
    }
}
