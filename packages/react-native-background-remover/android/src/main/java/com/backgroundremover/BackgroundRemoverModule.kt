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

class BackgroundRemoverModule internal constructor(context: ReactApplicationContext) :
  BackgroundRemoverSpec(context) {
  private var segmenter: SubjectSegmenter? = null

  override fun getName(): String {
    return NAME
  }

  @ReactMethod
  override fun removeBackground(imageURI: String, promise: Promise) {
    val segmenter = this.segmenter ?: createSegmenter()
    val image = getImageBitmap(imageURI)

    val inputImage = InputImage.fromBitmap(image, 0)

    segmenter.process(inputImage).addOnFailureListener { e ->
      promise.reject(e)
    }.addOnSuccessListener { result ->
      // Get the foreground bitmap directly from the result
      val foregroundBitmap = result.foregroundBitmap
      
      if (foregroundBitmap != null) {
        // Crop transparent pixels around the image
        val croppedBitmap = cropTransparentPixels(foregroundBitmap)
        val fileName = URI(imageURI).path.split("/").last()
        val savedImageURI = saveImage(croppedBitmap, fileName)
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

  private fun cropTransparentPixels(bitmap: Bitmap): Bitmap {
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
