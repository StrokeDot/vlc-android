@file:Suppress("UNUSED_PARAMETER", "unused")
package android.media
import java.nio.ByteBuffer
class MediaFormat { companion object { const val KEY_MIME = "mime"; const val KEY_SAMPLE_RATE = "sr"; const val KEY_CHANNEL_COUNT = "cc"; const val KEY_AAC_PROFILE = "p"; const val KEY_BIT_RATE = "b"; const val KEY_MAX_INPUT_SIZE = "m"; const val MIMETYPE_AUDIO_AAC = "audio/mp4a-latm"
    fun createAudioFormat(mime: String, rate: Int, ch: Int) = MediaFormat() }
  fun getString(k: String): String? = null; fun getInteger(k: String) = 0; fun setInteger(k: String, v: Int) {}; fun setByteBuffer(k: String, b: ByteBuffer) {} }
class MediaCodec { class BufferInfo { var offset = 0; var size = 0; var presentationTimeUs = 0L; var flags = 0 }
  companion object { const val INFO_OUTPUT_FORMAT_CHANGED = -2; const val BUFFER_FLAG_END_OF_STREAM = 4; const val BUFFER_FLAG_CODEC_CONFIG = 2; const val CONFIGURE_FLAG_ENCODE = 1
    fun createDecoderByType(t: String) = MediaCodec(); fun createEncoderByType(t: String) = MediaCodec() }
  fun configure(f: MediaFormat, s: Any?, c: Any?, flags: Int) {}; fun start() {}; fun stop() {}; fun release() {}
  fun dequeueInputBuffer(t: Long) = 0; fun dequeueOutputBuffer(i: BufferInfo, t: Long) = 0; fun getInputBuffer(i: Int): ByteBuffer? = null; fun getOutputBuffer(i: Int): ByteBuffer? = null
  fun queueInputBuffer(i: Int, o: Int, s: Int, t: Long, f: Int) {}; fun releaseOutputBuffer(i: Int, r: Boolean) {}; val outputFormat = MediaFormat() }
class MediaCodecInfo { class CodecProfileLevel { companion object { const val AACObjectLC = 2 } } }
class MediaCodecList(k: Int) { companion object { const val REGULAR_CODECS = 0 }; fun findDecoderForFormat(f: MediaFormat): String? = null }
