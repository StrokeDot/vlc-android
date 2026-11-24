@file:Suppress("UNUSED_PARAMETER", "unused")
package android.os
object SystemClock { fun elapsedRealtime() = 0L }
class ParcelFileDescriptor : java.io.Closeable { val fileDescriptor = java.io.FileDescriptor(); override fun close() {} }
