package com.mobilerun.portal.streaming

import android.media.MediaCodecInfo
import android.os.Build
import com.mobilerun.portal.service.CaptureSizing
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.Predicate
import org.webrtc.VideoCodecInfo

/** Uses WebRTC's own MediaCodecList ordering, color-format checks and SDK hardware rules. */
internal object TvEncoderCapabilities {
    data class Encoder(
        val name: String,
        val hardware: Boolean,
        val supports: (Int, Int, Int) -> Boolean,
        val performanceCovers: ((Int, Int, Int) -> Boolean)? = null,
    )

    data class Decision(val encoder: Encoder?, val box: Pair<Int, Int>, val reason: String) {
        val h264Only: Boolean get() = encoder != null
        val allowedVideoCodecs: Set<String> get() = if (h264Only) setOf("H264") else setOf("H264", "VP8")
    }

    fun select(encoders: List<Encoder>, upperBound: Pair<Int, Int>? = null): Decision {
        // Do not skip a slower first encoder: WebRTC will select that same encoder.
        val encoder = encoders.firstOrNull { it.hardware }
        val capabilityBox = listOf(1280 to 720, 960 to 540).firstOrNull { (width, height) ->
            encoder != null && runCatching {
                encoder.supports(width, height, 30) &&
                    (encoder.performanceCovers?.invoke(width, height, 30) ?: true)
            }.getOrDefault(false)
        }
        val base = capabilityBox ?: (CaptureSizing.DEFAULT_CAPTURE_WIDTH to CaptureSizing.DEFAULT_CAPTURE_HEIGHT)
        val bounded = upperBound?.let { minOf(base.first, it.first) to minOf(base.second, it.second) } ?: base
        return Decision(encoder, bounded, if (capabilityBox != null) "capability" else "fallback")
    }

    fun probe(): List<Encoder> {
        val candidates = mutableListOf<MediaCodecInfo>()
        // Reject after recording: findCodecForType(H264) enumerates eligible AVC encoders,
        // but returns null, so createEncoder never constructs or initializes a codec/GL resource.
        runCatching {
            HardwareVideoEncoderFactory(null, true, true, Predicate { info ->
                candidates.add(info)
                false
            }).createEncoder(VideoCodecInfo("H264", emptyMap(), emptyList()))
        }
        val first = candidates.firstOrNull() ?: return emptyList()
        // The factory has already applied isHardwareAccelerated on API 29+, or its SDK rules below 29.
        // Failure to read video/performance capabilities keeps AVC eligible but selects the legacy box.
        val video = runCatching { first.getCapabilitiesForType("video/avc").videoCapabilities }.getOrNull()
        var performanceReadFailed = false
        val performance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && video != null) {
            runCatching { video.supportedPerformancePoints }.getOrElse {
                performanceReadFailed = true
                null
            }?.takeIf { it.isNotEmpty() }
        } else null
        return listOf(Encoder(
            name = first.name,
            hardware = true,
            supports = { width, height, fps -> !performanceReadFailed && video?.areSizeAndRateSupported(width, height, fps.toDouble()) == true },
            performanceCovers = performance?.let { points ->
                { width, height, fps ->
                    points.any { it.covers(MediaCodecInfo.VideoCapabilities.PerformancePoint(width, height, fps)) }
                }
            },
        ))
    }
}
