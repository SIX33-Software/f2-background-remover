package com.backgroundremover

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.Future

import com.facebook.react.bridge.ReadableMap

class BackgroundRemoverModule internal constructor(context: ReactApplicationContext) :
  BackgroundRemoverSpec(context) {
  private var segmenter: SubjectSegmenter? = null

  override fun getName(): String {
    return NAME
  }

  @ReactMethod
  override fun removeBackground(imageURI: String, options: ReadableMap, promise: Promise) {
    val trim = if (options.hasKey("trim")) options.getBoolean("trim") else true
    val segmenter = this.segmenter ?: createSegmenter()
    val image = getImageBitmap(imageURI)

    val inputImage = InputImage.fromBitmap(image, 0)

    segmenter.process(inputImage).addOnFailureListener { e ->
      promise.reject(e)
    }.addOnSuccessListener { result ->
      // Get the foreground bitmap directly from the result
      val foregroundBitmap = result.foregroundBitmap
      
      if (foregroundBitmap != null) {
        val finalBitmap = if (trim) {
          // Trim transparent pixels around the image
          trimTransparentPixels(foregroundBitmap)
        } else {
          foregroundBitmap
        }
        val fileName = URI(imageURI).path.split("/").last()
        val savedImageURI = saveImage(finalBitmap, fileName)
        promise.resolve(savedImageURI)
      } else {
        promise.reject("BackgroundRemover", "No foreground detected", null)
      }
    }
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

    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      ImageDecoder.decodeBitmap(
        ImageDecoder.createSource(
          reactApplicationContext.contentResolver,
          uri
        )
      ).copy(Bitmap.Config.ARGB_8888, true)
    } else {
      MediaStore.Images.Media.getBitmap(reactApplicationContext.contentResolver, uri)
    }

    return bitmap
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
}
