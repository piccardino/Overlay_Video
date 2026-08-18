package com.example.vhpmatchpresentation.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.widget.ImageView
import coil.load
import com.example.vhpmatchpresentation.R

object ImageHelper {

    fun loadPlayerPhoto(context: Context, imageView: ImageView, photoUri: String?) {
        if (photoUri.isNullOrEmpty()) {
            imageView.setImageResource(R.drawable.ic_player_silhouette)
            return
        }

        // 1. Check if Base64 encoded image (data:image/... or raw base64)
        if (photoUri.startsWith("data:image") || (!photoUri.startsWith("http") && !photoUri.startsWith("content") && !photoUri.startsWith("file") && photoUri.length > 50)) {
            try {
                val cleanBase64 = if (photoUri.contains(",")) photoUri.substringAfter(",") else photoUri
                val decodedBytes = Base64.decode(cleanBase64.trim(), Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    return
                }
            } catch (e: Exception) {
                Log.e("ImageHelper", "Error decoding base64 player photo", e)
            }
        }

        // 2. Check if local content:// or file:// URI with EXIF rotation
        if (photoUri.startsWith("content://") || photoUri.startsWith("file://")) {
            try {
                val bytes = context.contentResolver.openInputStream(Uri.parse(photoUri))?.use { it.readBytes() }
                if (bytes != null) {
                    val exif = try {
                        ExifInterface(java.io.ByteArrayInputStream(bytes))
                    } catch (e: Exception) {
                        null
                    }
                    val orientation = exif?.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    ) ?: ExifInterface.ORIENTATION_NORMAL

                    var localBitmap: Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (localBitmap != null && orientation != ExifInterface.ORIENTATION_NORMAL) {
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
                        val rotated = Bitmap.createBitmap(localBitmap, 0, 0, localBitmap.width, localBitmap.height, matrix, true)
                        if (rotated != localBitmap) localBitmap.recycle()
                        localBitmap = rotated
                    }
                    if (localBitmap != null) {
                        imageView.setImageBitmap(localBitmap)
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e("ImageHelper", "Error decoding local photo URI", e)
            }
        }

        // 3. Remote HTTP / HTTPS URL via Coil
        imageView.load(photoUri) {
            placeholder(R.drawable.ic_player_silhouette)
            error(R.drawable.ic_player_silhouette)
        }
    }
}
