package com.matejdro.pebblenotificationcenter.notification.parsing

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import androidx.core.graphics.drawable.toBitmapOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.io.FileNotFoundException

fun interface BitmapLoader {
   fun getBitmap(icon: Icon): Bitmap?
}

@ContributesBinding(AppScope::class)
class BitmapLoaderImpl @Inject constructor(private val context: Context) : BitmapLoader {
   override fun getBitmap(icon: Icon): Bitmap? = getBitmapFromUriIcon(icon)

   @SuppressLint("NewApi") // Icon.getUri() is API 23+; minSdk 24 covers it
   @Suppress("UseKtx", "SwallowedException")
   private fun getBitmapFromUriIcon(icon: Icon): Bitmap? {
      return if (icon.uri != null) {
         try {
            context.contentResolver.openInputStream(icon.uri)?.use { stream ->
               val bitmap = BitmapFactory.decodeStream(stream) ?: return@use null
               val scaledHeight = LARGE_BITMAP_WIDTH_SIZE * bitmap.height / bitmap.width
               if (bitmap.width <= LARGE_BITMAP_WIDTH_SIZE) return@use bitmap
               Bitmap.createScaledBitmap(bitmap, LARGE_BITMAP_WIDTH_SIZE, scaledHeight, true)
            }
         } catch (e: SecurityException) {
            null // FileProvider not exported or permission denied
         } catch (e: IllegalArgumentException) {
            null // Invalid URI
         } catch (e: FileNotFoundException) {
            null // URI no longer available
         }
      } else {
         val drawable = icon.loadDrawable(context) ?: return null
         drawable.toBitmapOrNull(
            width = LARGE_BITMAP_WIDTH_SIZE,
            height = LARGE_BITMAP_WIDTH_SIZE * drawable.intrinsicHeight / drawable.intrinsicWidth
         )
      }
   }
}

// Pick something that is large enough to display nicely on all watches, but small enough to not overfill the ram
private const val LARGE_BITMAP_WIDTH_SIZE = 512
