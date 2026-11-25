package com.example.storevisitmanagement.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

object ImageUtils {

    fun createTempImageFile(context: Context): File {
        val photosDir = File(context.filesDir, "photos").apply {
            if (!exists()) mkdirs()
        }
        return File(photosDir, "temp_capture_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
    }

    fun createTempImageUri(context: Context, file: File): Uri {
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, file)
    }

    suspend fun compressAndSaveImage(context: Context, sourceFile: File): String? {
        return withContext(Dispatchers.IO) {
            var tempDestFile: File? = null
            var destFile: File? = null
            try {
                if (!sourceFile.exists() || sourceFile.length() <= 0) {
                    return@withContext null
                }

                val photosDir = File(context.filesDir, "photos").apply {
                    if (!exists()) mkdirs()
                }

                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(sourceFile.absolutePath, options)

                val origWidth = options.outWidth
                val origHeight = options.outHeight
                if (origWidth <= 0 || origHeight <= 0) {
                    return@withContext null
                }

                // Calculate sample size from the longest dimension (maxDim = 1280px)
                val maxDim = 1280
                var sampleSize = 1
                val longestDim = maxOf(origWidth, origHeight)
                while (longestDim / (sampleSize * 2) >= maxDim) {
                    sampleSize *= 2
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }
                var bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOptions) ?: return@withContext null

                // Handle EXIF orientation and mirroring correctly (all 8 orientations)
                try {
                    val exif = ExifInterface(sourceFile.absolutePath)
                    val orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                    val matrix = Matrix()
                    when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> {
                            matrix.postRotate(90f)
                            matrix.postScale(-1f, 1f)
                        }
                        ExifInterface.ORIENTATION_TRANSVERSE -> {
                            matrix.postRotate(270f)
                            matrix.postScale(-1f, 1f)
                        }
                    }

                    if (!matrix.isIdentity) {
                        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        if (rotated !== bitmap) {
                            bitmap.recycle()
                            bitmap = rotated
                        }
                    }
                } catch (e: Exception) {
                    // Ignore EXIF parsing errors
                }

                // Final bounded scaling step if still slightly above maxDim
                if (bitmap.width > maxDim || bitmap.height > maxDim) {
                    val scaleFactor = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                    val scaledWidth = (bitmap.width * scaleFactor).toInt()
                    val scaledHeight = (bitmap.height * scaleFactor).toInt()
                    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
                    if (scaledBitmap !== bitmap) {
                        bitmap.recycle()
                        bitmap = scaledBitmap
                    }
                }

                tempDestFile = File(photosDir, "img_temp_${UUID.randomUUID()}.jpg")
                var compressSuccess = false
                FileOutputStream(tempDestFile).use { out ->
                    compressSuccess = bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    out.flush()
                }
                bitmap.recycle()

                if (compressSuccess && tempDestFile.exists() && tempDestFile.length() > 0) {
                    destFile = File(photosDir, "img_${UUID.randomUUID()}.jpg")
                    val promoted = tempDestFile.renameTo(destFile)
                    if (promoted && destFile.exists() && destFile.length() > 0) {
                        destFile.absolutePath
                    } else {
                        try { tempDestFile.delete() } catch (_: Exception) {}
                        try { destFile?.delete() } catch (_: Exception) {}
                        null
                    }
                } else {
                    try { tempDestFile.delete() } catch (_: Exception) {}
                    null
                }
            } catch (e: Exception) {
                try { tempDestFile?.delete() } catch (_: Exception) {}
                try { destFile?.delete() } catch (_: Exception) {}
                null
            } finally {
                try { sourceFile.delete() } catch (_: Exception) {}
            }
        }
    }
}
