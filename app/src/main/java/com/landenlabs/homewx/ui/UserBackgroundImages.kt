package com.landenlabs.homewx.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.util.Locale

/**
 * Weather backgrounds supplied by the user in a folder chosen in Settings (the built-in photos are
 * used for any condition with no matching file). Files are named `wx_bg_<clear|cloudy|rainy|windy>`,
 * optionally followed by `_anything`, then `.jpg`, `.jpeg`, `.png` or `.webp` - e.g.
 * `wx_bg_clear.jpg`, `wx_bg_clear_002.png`, `wx_bg_clear_bigsky.webp`.
 *
 * Each condition keeps its own list of images. [pick] hands out the same image for an hour, then
 * steps to the next one (wrapping), so a panel left running rotates through the set instead of
 * showing one photo forever - see [HourlyRotation].
 */
class UserBackgroundImages(private val context: Context) {

    @Volatile
    private var images: Map<WeatherBackground, List<Uri>> = emptyMap()
    private val rotation = HourlyRotation<WeatherBackground>()

    /** Replaces the image lists with a fresh scan of [folder] (blocking - call off the main thread).
     *  Positions are kept, so an unchanged folder doesn't jump back to a different image. */
    fun reload(folder: Uri?) {
        images = if (folder == null) emptyMap() else scanFolder(context, folder)
    }

    /** The image [background] should show at [nowMillis], or null to use the built-in photo. */
    fun pick(background: WeatherBackground, nowMillis: Long): Uri? =
        rotation.pick(background, images[background].orEmpty(), nowMillis)

    /** Forgets [uri] after it failed to load (deleted or unreadable), so [pick] moves on to another. */
    @Synchronized
    fun drop(uri: Uri) {
        images = images.mapValues { (_, list) -> list - uri }
    }

    /** Decodes [uri] scaled so its longest side is about [maxPx] and upright per its EXIF tag;
     *  null if it can't be read. Blocking. */
    fun decode(uri: Uri, maxPx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        bitmap?.let { upright(it, uri) }
    } catch (e: Exception) {
        Log.w(TAG, "decode failed: $uri", e)
        null
    }

    private fun upright(bitmap: Bitmap, uri: Uri): Bitmap {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (e: Exception) {
            null
        } ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }

    companion object {
        private const val TAG = "UserBackgroundImages"
        private const val HOUR_MILLIS = 60 * 60 * 1000L

        /** wx_bg_<condition>, then nothing or "_" plus anything, then a supported extension. */
        private val FILE_NAME = Regex(
            "^wx_bg_(${WeatherBackground.values().joinToString("|") { it.key }})(_.*)?\\.(jpe?g|png|webp)$",
            RegexOption.IGNORE_CASE
        )

        /** Lists the matching files directly inside [folder] (a tree URI from the folder picker),
         *  grouped by condition and sorted by name. Empty if the folder can't be read. Blocking. */
        fun scanFolder(context: Context, folder: Uri): Map<WeatherBackground, List<Uri>> {
            val found = mutableMapOf<WeatherBackground, MutableList<Pair<String, Uri>>>()
            try {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    folder, DocumentsContract.getTreeDocumentId(folder)
                )
                val columns = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )
                context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1) ?: continue
                        if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                        val condition = FILE_NAME.matchEntire(name)?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: continue
                        val background = WeatherBackground.values().first { it.key == condition }
                        found.getOrPut(background) { mutableListOf() }
                            .add(name to DocumentsContract.buildDocumentUriUsingTree(folder, cursor.getString(0)))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "scanFolder failed: $folder", e)
            }
            return found.mapValues { (_, files) -> files.sortedBy { it.first.lowercase(Locale.ROOT) }.map { it.second } }
        }

        /** The folder's display name, for the Settings status line; null if unreadable. Blocking. */
        fun folderName(context: Context, folder: Uri): String? = try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }
}
