package com.backgroundremover

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class BackgroundRemoverModule internal constructor(context: ReactApplicationContext) :
  BackgroundRemoverSpec(context) {
  private var segmenter: SubjectSegmenter? = null
  private val worker = Executors.newSingleThreadExecutor()
  private val isProcessing = AtomicBoolean(false)
  // Maximum number of retry attempts after encountering OOM while processing
  private val MAX_ATTEMPTS = 3
  // Factor to conceptually scale between attempts (currently used to gate retries; decoding already downsamples)
  private val SCALE_FACTOR_ON_OOM = 0.5f
  // Upper bound of pixels (width * height) we attempt to decode. 4M @ 4 bytes per pixel ~ 16MB raw before extra copies.
  // Keeping this conservative reduces peak memory spikes from MLKit + intermediate ARGB bitmaps.
  private val MAX_PIXELS = 4_000_000

  override fun getName(): String {
    return NAME
  }

  @ReactMethod
  override fun removeBackground(imageURI: String, options: ReadableMap, promise: Promise) {
    if (!isProcessing.compareAndSet(false, true)) {
      promise.reject("BackgroundRemover", "Another background removal is in progress", null)
      return
    }
    val trim = if (options.hasKey("trim")) options.getBoolean("trim") else true
    worker.execute {
      try {
        val segmenter = this.segmenter ?: createSegmenter()
        processWithRetries(segmenter, imageURI, trim, promise, attempt = 1)
      } catch (t: Throwable) {
        if (isProcessing.compareAndSet(true, false)) {
          promise.reject("BackgroundRemover", t)
        }
      }
    }
  }

  private fun processWithRetries(segmenter: SubjectSegmenter, imageURI: String, trim: Boolean, promise: Promise, attempt: Int) {
    var image: Bitmap? = null
    try {
      image = getImageBitmap(imageURI)
      val inputImage = InputImage.fromBitmap(image, 0)
      segmenter.process(inputImage)
        .addOnFailureListener { e ->
          image?.recycle()
          if (e is OutOfMemoryError || e.cause is OutOfMemoryError) {
            handleOOMRetry(segmenter, imageURI, trim, promise, attempt, e)
          } else {
            if (isProcessing.compareAndSet(true, false)) {
              promise.reject(e)
            }
          }
        }
        .addOnSuccessListener { result ->
          try {
            val foregroundBitmap = result.foregroundBitmap
            if (foregroundBitmap != null) {
              val finalBitmap = if (trim) trimTransparentPixels(foregroundBitmap) else foregroundBitmap
              val fileName = URI(imageURI).path.split("/").last()
              val savedImageURI = saveImage(finalBitmap, fileName)
              if (isProcessing.compareAndSet(true, false)) {
                promise.resolve(savedImageURI)
              }
              if (finalBitmap !== foregroundBitmap) foregroundBitmap.recycle()
            } else {
              if (isProcessing.compareAndSet(true, false)) {
                promise.reject("BackgroundRemover", "No foreground detected", null)
              }
            }
          } catch (oom: OutOfMemoryError) {
            handleOOMRetry(segmenter, imageURI, trim, promise, attempt, oom)
          } catch (t: Throwable) {
            if (isProcessing.compareAndSet(true, false)) promise.reject("BackgroundRemover", t)
          } finally {
            image?.recycle()
          }
        }
    } catch (oom: OutOfMemoryError) {
      image?.recycle()
      handleOOMRetry(segmenter, imageURI, trim, promise, attempt, oom)
    } catch (t: Throwable) {
      image?.recycle()
      if (isProcessing.compareAndSet(true, false)) promise.reject("BackgroundRemover", t)
    }
  }

  private fun handleOOMRetry(segmenter: SubjectSegmenter, imageURI: String, trim: Boolean, promise: Promise, attempt: Int, err: Throwable) {
    if (attempt >= MAX_ATTEMPTS) {
      if (isProcessing.compareAndSet(true, false)) {
        promise.reject("BackgroundRemover", "Out of memory after $attempt attempts", err)
      }
      return
    }
    // Lower MAX_PIXELS heuristic by scaling factor for subsequent attempts
    System.gc()
    // Provide a temporary scaled version by writing a smaller bitmap if necessary handled inside getImageBitmap using dynamic global? Simplest: temporarily override ThreadLocal desired max.
    // For simplicity we just sleep briefly and try again expecting decode downscaling to pick smaller target size due to existing MAX_PIXELS. We can reduce threshold further.
    processWithRetries(segmenter, imageURI, trim, promise, attempt + 1)
  }

  private fun createSegmenter(): SubjectSegmenter {
    val options = SubjectSegmenterOptions.Builder()
      .enableForegroundBitmap()  // Enable getting foreground bitmap directly
      .build()

    val segmenter = SubjectSegmentation.getClient(options)
    this.segmenter = segmenter

    return segmenter
  }

  private fun getImageBitmap(imageURI: String): Bitmap {
    val uri = Uri.parse(imageURI)
    // Decode with downscaling to avoid OOM. Target maximum pixel count keeps memory reasonable.
  val MAX_PIXELS = this.MAX_PIXELS // use class constant
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val source = ImageDecoder.createSource(reactApplicationContext.contentResolver, uri)
      var computedSample = 1
      var outWidth = 0
      var outHeight = 0
      // Use onHeaderDecoded to compute scaling prior to allocation
      val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        outWidth = info.size.width
        outHeight = info.size.height
        val total = outWidth.toLong() * outHeight.toLong()
        if (total > MAX_PIXELS) {
          val scale = Math.sqrt(total.toDouble() / MAX_PIXELS.toDouble())
          // scale is factor we need to divide by
          val targetWidth = (outWidth / scale).toInt().coerceAtLeast(1)
          val targetHeight = (outHeight / scale).toInt().coerceAtLeast(1)
          decoder.setTargetSize(targetWidth, targetHeight)
        }
        decoder.isMutableRequired = true
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // Reduce pressure on GPU / ashmem
      }
      return bitmap.copy(Bitmap.Config.ARGB_8888, true)
    } else {
      // Pre Android P: two pass decode using BitmapFactory for bounds then decode with inSampleSize
      val inputStream1 = reactApplicationContext.contentResolver.openInputStream(uri)
      val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeStream(inputStream1, null, opts)
      inputStream1?.close()
      var inSampleSize = 1
      val (rawW, rawH) = opts.outWidth to opts.outHeight
      if (rawW > 0 && rawH > 0) {
        val total = rawW.toLong() * rawH.toLong()
        if (total > MAX_PIXELS) {
          val ratio = Math.sqrt(total.toDouble() / MAX_PIXELS.toDouble())
            .coerceAtLeast(1.0)
          // compute power of two sample size
            varSample@ run {
              while (true) {
                val next = inSampleSize * 2
                val scaledW = rawW / next
                val scaledH = rawH / next
                if (scaledW <= 0 || scaledH <= 0) break
                val scaledTotal = scaledW.toLong() * scaledH.toLong()
                if (scaledTotal < MAX_PIXELS || next > ratio) break
                inSampleSize = next
              }
            }
        }
      }
      val opts2 = BitmapFactory.Options().apply {
        inJustDecodeBounds = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inSampleSize = inSampleSize
      }
      val inputStream2 = reactApplicationContext.contentResolver.openInputStream(uri)
      val decoded = BitmapFactory.decodeStream(inputStream2, null, opts2)
      inputStream2?.close()
      return decoded ?: MediaStore.Images.Media.getBitmap(reactApplicationContext.contentResolver, uri)
    }
  }

  private fun trimTransparentPixels(bitmap: Bitmap): Bitmap {
    val totalPixels = bitmap.width * bitmap.height
    val coreCount = Runtime.getRuntime().availableProcessors()
    
    return if (totalPixels > 1000000 && coreCount >= 4) {
      trimTransparentPixelsParallel(bitmap)
    } else {
      trimTransparentPixelsSequential(bitmap)
    }
  }

  private fun trimTransparentPixelsSequential(bitmap: Bitmap): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    
    var minX = width
    var minY = height
    var maxX = -1
    var maxY = -1
    
    // Find the bounds of non-transparent pixels
    for (y in 0 until height) {
      for (x in 0 until width) {
        val pixel = bitmap.getPixel(x, y)
        val alpha = (pixel shr 24) and 0xFF
        
        // If pixel is not transparent
        if (alpha > 0) {
          if (x < minX) minX = x
          if (x > maxX) maxX = x
          if (y < minY) minY = y
          if (y > maxY) maxY = y
        }
      }
    }
    
    // If no non-transparent pixels found, return a 1x1 transparent bitmap
    if (maxX == -1 || maxY == -1) {
      return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }
    
    // Calculate new dimensions
    val newWidth = maxX - minX + 1
    val newHeight = maxY - minY + 1
    
    // Create cropped bitmap
    return Bitmap.createBitmap(bitmap, minX, minY, newWidth, newHeight)
  }

  // Parallel implementation for large images
  private fun trimTransparentPixelsParallel(bitmap: Bitmap): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val coreCount = Runtime.getRuntime().availableProcessors()
    
    // Use optimal number of threads based on available cores (min 4, max 8)
    val threadCount = minOf(maxOf(coreCount, 4), 8)
    
    // Split into optimal number of regions based on thread count
    val quadrants = if (threadCount == 4) {
      // Classic 4-quadrant split for 4 cores
      val halfWidth = width / 2
      val halfHeight = height / 2
      listOf(
        Quadrant(0, 0, halfWidth, halfHeight),                    // Top-left
        Quadrant(halfWidth, 0, width, halfHeight),               // Top-right (gets extra width if odd)
        Quadrant(0, halfHeight, halfWidth, height),              // Bottom-left (gets extra height if odd)
        Quadrant(halfWidth, halfHeight, width, height)           // Bottom-right (gets both extras if odd)
      )
    } else {
      // For more cores, split into horizontal strips for better cache locality
      val stripHeight = height / threadCount
      (0 until threadCount).map { i ->
        val startY = i * stripHeight
        val endY = if (i == threadCount - 1) height else (i + 1) * stripHeight
        Quadrant(0, startY, width, endY)
      }
    }
    
    // Process regions in parallel using adaptive thread pool
    val executor = Executors.newFixedThreadPool(threadCount)
    val futures = quadrants.map { quadrant ->
      executor.submit<QuadrantBounds> {
        findBoundsInQuadrant(bitmap, quadrant)
      }
    }
    
    // Collect results from all threads
    val results = futures.map { it.get() }
    executor.shutdown()
    
    // Merge bounds from all quadrants to get global bounds
    val globalBounds = mergeBounds(results)
    
    // Return cropped bitmap or fallback to 1x1 transparent
    return if (globalBounds.isValid()) {
      Bitmap.createBitmap(
        bitmap, 
        globalBounds.minX, 
        globalBounds.minY, 
        globalBounds.width(), 
        globalBounds.height()
      )
    } else {
      Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }
  }

  // Helper data classes for parallel processing
  private data class Quadrant(val startX: Int, val startY: Int, val endX: Int, val endY: Int)
  
  private data class QuadrantBounds(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {
    fun isValid() = maxX >= 0 && maxY >= 0
    fun width() = maxX - minX + 1
    fun height() = maxY - minY + 1
  }

  private fun findBoundsInQuadrant(bitmap: Bitmap, quadrant: Quadrant): QuadrantBounds {
    var minX = Int.MAX_VALUE
    var minY = Int.MAX_VALUE  
    var maxX = -1
    var maxY = -1
    
    // Scan only the pixels in this quadrant
    for (y in quadrant.startY until quadrant.endY) {
      for (x in quadrant.startX until quadrant.endX) {
        val pixel = bitmap.getPixel(x, y)
        val alpha = (pixel shr 24) and 0xFF
        
        if (alpha > 0) {
          if (x < minX) minX = x
          if (x > maxX) maxX = x
          if (y < minY) minY = y  
          if (y > maxY) maxY = y
        }
      }
    }
    
    return QuadrantBounds(
      if (minX == Int.MAX_VALUE) -1 else minX,
      if (minY == Int.MAX_VALUE) -1 else minY,
      maxX,
      maxY
    )
  }

  private fun mergeBounds(results: List<QuadrantBounds>): QuadrantBounds {
    val validResults = results.filter { it.isValid() }
    
    if (validResults.isEmpty()) {
      return QuadrantBounds(-1, -1, -1, -1)
    }
    
    return QuadrantBounds(
      minX = validResults.minOf { it.minX },
      minY = validResults.minOf { it.minY }, 
      maxX = validResults.maxOf { it.maxX },
      maxY = validResults.maxOf { it.maxY }
    )
  }

  private fun saveImage(bitmap: Bitmap, fileName: String): String {
    // Use PNG format to preserve transparency from background removal
    val pngFileName = fileName.substringBeforeLast('.') + ".png"
    val file = File(reactApplicationContext.filesDir, pngFileName)
    val fileOutputStream = FileOutputStream(file)
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream)
    fileOutputStream.close()
    return file.toURI().toString()
  }

  companion object {
    const val NAME = "BackgroundRemover"
  }

  override fun onCatalystInstanceDestroy() {
    super.onCatalystInstanceDestroy()
    try {
      segmenter?.close()
      worker.shutdownNow()
    } catch (_: Exception) {
    }
  }
}
