@file:Suppress("UNUSED_PARAMETER", "unused")
package android.content
class AssetFileDescriptor : java.io.Closeable { val length = 0L; override fun close() {} }
class ContentResolver { fun openFileDescriptor(u: android.net.Uri, m: String): android.os.ParcelFileDescriptor? = null; fun openAssetFileDescriptor(u: android.net.Uri, m: String): AssetFileDescriptor? = null }
open class Context { val contentResolver = ContentResolver(); val applicationContext: Context get() = this }
