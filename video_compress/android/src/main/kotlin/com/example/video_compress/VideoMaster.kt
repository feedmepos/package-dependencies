package com.example.video_compress

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaCodec
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.source.ClipDataSource
import com.otaliastudios.transcoder.source.UriDataSource
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * The video master (FeedVibe `media §6.3`): a probe, a pass-through trim or a single 1080p
 * transcode, and the first frame at full resolution. Which of the two to write is decided by the
 * caller from [probe]; this only carries it out.
 */
class VideoMaster(private val context: Context) {

    private fun outDir(): File =
        File(context.getExternalFilesDir("video_compress"), "master").apply { mkdirs() }

    /** Display size (after rotation), duration, frame rate, bitrate, codec and file size. */
    fun probe(path: String): JSONObject {
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            retriever.setDataSource(context, Uri.parse(path))
            extractor.setDataSource(context, Uri.parse(path), null)

            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    format = candidate
                    break
                }
            }

            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
            val rawWidth = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val rawHeight = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            val quarterTurned = rotation == 90 || rotation == 270
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            val fileSize = File(Uri.parse(path).path ?: path).length()

            val frameRate = if (format?.containsKey(MediaFormat.KEY_FRAME_RATE) == true) {
                format.getInteger(MediaFormat.KEY_FRAME_RATE)
            } else {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()?.toInt() ?: 30
            }
            // Many containers leave the track bitrate out; the file's average stands in.
            val bitrate = if (format?.containsKey(MediaFormat.KEY_BIT_RATE) == true) {
                format.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
            } else if (durationMs > 0) {
                fileSize * 8 * 1000 / durationMs
            } else {
                0L
            }
            val codec = when (format?.getString(MediaFormat.KEY_MIME)) {
                MediaFormat.MIMETYPE_VIDEO_AVC -> "h264"
                MediaFormat.MIMETYPE_VIDEO_HEVC -> "hevc"
                else -> format?.getString(MediaFormat.KEY_MIME) ?: "unknown"
            }

            return JSONObject()
                .put("width", if (quarterTurned) rawHeight else rawWidth)
                .put("height", if (quarterTurned) rawWidth else rawHeight)
                .put("durationMs", durationMs)
                .put("frameRate", frameRate)
                .put("bitrate", bitrate)
                .put("codec", codec)
                .put("fileSize", fileSize)
                .put("hevcEncoder", hasHevcEncoder())
        } finally {
            retriever.release()
            extractor.release()
        }
    }

    /** An HEVC encoder that takes a 1080p frame either way up; a smaller one cannot be used. */
    private fun hasHevcEncoder(): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { type ->
                type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) &&
                    info.getCapabilitiesForType(type).videoCapabilities?.let {
                        it.isSizeSupported(1080, 1920) && it.isSizeSupported(1920, 1080)
                    } == true
            }
        }

    /**
     * Writes the master. [bitrate] null means pass-through: the samples in the cut are copied into
     * a fresh file, none re-encoded. Otherwise one transcode, short edge 1080, at [bitrate] bps and
     * the input frame rate capped at [frameRate], HEVC when [hevc]. Location metadata is never
     * carried: the muxer writes a fresh file. Answers the output path, or null on failure or
     * cancellation.
     */
    fun prepare(
        path: String,
        startMs: Long?,
        endMs: Long?,
        bitrate: Long?,
        frameRate: Int,
        hevc: Boolean,
        onProgress: (Double) -> Unit,
        result: MethodChannel.Result,
    ): Future<Void> {
        val out = File(outDir(), "master-${UUID.randomUUID()}.mp4").absolutePath
        if (bitrate == null) return passThrough(path, startMs, endMs, out, result)

        val source = UriDataSource(context, Uri.parse(path))
        // Absolute clip times, in microseconds: the start, and the end when there is one.
        val clipped = when {
            endMs != null -> ClipDataSource(source, 1000 * (startMs ?: 0), 1000 * endMs)
            startMs != null -> ClipDataSource(source, 1000 * startMs)
            else -> source
        }

        val video = DefaultVideoStrategy.atMost(1080)
            .bitRate(bitrate)
            .frameRate(frameRate)
        if (hevc) video.mimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)

        return Transcoder.into(out)
            .addDataSource(clipped)
            .setVideoTrackStrategy(video.build())
            .setAudioTrackStrategy(
                DefaultAudioStrategy.builder()
                    .channels(DefaultAudioStrategy.CHANNELS_AS_INPUT)
                    .sampleRate(DefaultAudioStrategy.SAMPLE_RATE_AS_INPUT)
                    .bitRate(128_000)
                    .build()
            )
            .setListener(object : TranscoderListener {
                override fun onTranscodeProgress(progress: Double) = onProgress(progress * 100.0)
                override fun onTranscodeCompleted(successCode: Int) = result.success(out)
                override fun onTranscodeCanceled() = result.success(null)
                override fun onTranscodeFailed(exception: Throwable) {
                    File(out).delete()
                    result.success(null)
                }
            }).transcode()
    }

    /**
     * Copies the video and audio samples from the sync frame at or before [startMs] up to [endMs]
     * into [out], keeping the rotation tag. Transcoder cannot do this: it skips a job that copies
     * every track, and rotating forces a re-encode.
     */
    private fun passThrough(
        path: String,
        startMs: Long?,
        endMs: Long?,
        out: String,
        result: MethodChannel.Result,
    ): Future<Void> {
        val main = Handler(Looper.getMainLooper())
        return Executors.newSingleThreadExecutor().submit(Callable<Void> {
            val written = try {
                copySamples(path, startMs, endMs, out)
            } catch (e: Exception) {
                false
            }
            if (!written) File(out).delete()
            main.post { result.success(if (written) out else null) }
            null
        })
    }

    private fun copySamples(path: String, startMs: Long?, endMs: Long?, out: String): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, Uri.parse(path), null)
            muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val tracks = HashMap<Int, Int>()
            var bufferSize = 1 shl 20
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                extractor.selectTrack(i)
                tracks[i] = muxer.addTrack(format)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }
            if (tracks.isEmpty()) return false
            muxer.setOrientationHint(rotationOf(path))

            extractor.seekTo(1000 * (startMs ?: 0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val baseUs = extractor.sampleTime.coerceAtLeast(0)
            val endUs = endMs?.let { 1000 * it } ?: Long.MAX_VALUE

            muxer.start()
            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()
            while (!Thread.currentThread().isInterrupted) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val time = extractor.sampleTime
                if (time > endUs) break
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                if (time >= baseUs) {
                    info.set(
                        0,
                        size,
                        time - baseUs,
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else {
                            0
                        },
                    )
                    muxer.writeSampleData(tracks.getValue(track), buffer, info)
                }
                extractor.advance()
            }
            if (Thread.currentThread().isInterrupted) return false
            muxer.stop()
            return true
        } finally {
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }

    private fun rotationOf(path: String): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(path))
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
        } finally {
            retriever.release()
        }
    }

    /** Frame 0 at full display resolution, as a lossless PNG; the caller does the one encode. */
    fun firstFrame(path: String): String? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(path))
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val out = File(outDir(), "frame-${UUID.randomUUID()}.png")
            FileOutputStream(out).use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            frame.recycle()
            return out.absolutePath
        } catch (e: Exception) {
            return null
        } finally {
            retriever.release()
        }
    }
}
