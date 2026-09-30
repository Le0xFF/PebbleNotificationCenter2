package com.matejdro.pebblenotificationcenter.bluetooth.images

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.math.roundToInt

// Process-wide (AppScope) so the cache survives WatchappConnectionScope churn:
// every DrawableExtractorImpl instance created by a new watch-app connection shares this object,
// so URIs whose Android content-grant was revoked after the first read are never re-opened.
@Inject
@SingleIn(AppScope::class)
class IconBitmapCache {
   private val cache = object : LinkedHashMap<Uri, Bitmap>(CAPACITY, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, Bitmap>): Boolean {
         return size > CAPACITY
      }
   }

   @Synchronized
   fun get(uri: Uri): Bitmap? {
      val bitmap = cache[uri]
      if (bitmap != null && bitmap.isRecycled) {
         cache.remove(uri)
         return null
      }
      return bitmap
   }

   @Synchronized
   fun put(uri: Uri, drawable: Drawable): Bitmap {
      val bitmap = (drawable as? BitmapDrawable)?.bitmap
      if (bitmap != null && bitmap.width <= MAX_DIMENSION && bitmap.height <= MAX_DIMENSION) {
         cache[uri] = bitmap
         return bitmap
      }

      val width = drawable.intrinsicWidth
      val height = drawable.intrinsicHeight
      val scaleFactor = 1f.coerceAtMost(MAX_DIMENSION.toFloat() / maxOf(width, height).toFloat())
      val boundedWidth = (width * scaleFactor).roundToInt()
      val boundedHeight = (height * scaleFactor).roundToInt()

      val boundedBitmap = Bitmap.createBitmap(boundedWidth, boundedHeight, Bitmap.Config.ARGB_8888)
      drawable.setBounds(0, 0, boundedWidth, boundedHeight)
      drawable.draw(Canvas(boundedBitmap))

      cache[uri] = boundedBitmap
      return boundedBitmap
   }
}

private const val MAX_DIMENSION = 1024
private const val CAPACITY = 8
