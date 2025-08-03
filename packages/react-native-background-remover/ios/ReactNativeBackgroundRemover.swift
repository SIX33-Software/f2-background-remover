import Vision
import CoreImage
import CoreImage.CIFilterBuiltins

public class BackgroundRemoverSwift: NSObject {
    
    @objc
    public func removeBackground(_ imageURI: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) -> Void {
        #if targetEnvironment(simulator)
        reject("BackgroundRemover", "SimulatorError", NSError(domain: "BackgroundRemover", code: 2))
        return
        #endif

        if #available(iOS 17.0, *) {
            guard let url = URL(string: imageURI) else {
                reject("BackgroundRemover", "Invalid URL", NSError(domain: "BackgroundRemover", code: 3))
                return
            }
            
            guard let originalImage = CIImage(contentsOf: url, options: [.applyOrientationProperty: true]) else {
                reject("BackgroundRemover", "Unable to load image", NSError(domain: "BackgroundRemover", code: 4))
                return
            }
            
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    // Create mask for foreground objects
                    guard let maskImage = self.createMask(from: originalImage) else {
                        reject("BackgroundRemover", "Failed to create mask", NSError(domain: "BackgroundRemover", code: 5))
                        return
                    }
                    
                    // Apply mask to remove background
                    let maskedImage = self.applyMask(mask: maskImage, to: originalImage)
                    
                    // Convert to UIImage
                    let uiImage = self.convertToUIImage(ciImage: maskedImage)
                    
                    // Crop transparent pixels around the image
                    let croppedImage = self.cropTransparentPixels(from: uiImage)
                    
                    // Save the image as PNG to preserve transparency
                    let tempURL = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(url.lastPathComponent).appendingPathExtension("png")
                    if let data = croppedImage.pngData() {
                        try data.write(to: tempURL)
                        DispatchQueue.main.async {
                            resolve(tempURL.absoluteString)
                        }
                    } else {
                        DispatchQueue.main.async {
                            reject("BackgroundRemover", "Error saving image", NSError(domain: "BackgroundRemover", code: 7))
                        }
                    }
                    
                } catch {
                    DispatchQueue.main.async {
                        reject("BackgroundRemover", "Error removing background", error)
                    }
                }
            }
        } else {
            // For iOS < 17.0, return a specific error code that indicates API fallback should be used
            reject("BackgroundRemover", "REQUIRES_API_FALLBACK", NSError(domain: "BackgroundRemover", code: 1001))
        }
    }
    
    // Crop transparent pixels around the image
    private func cropTransparentPixels(from image: UIImage) -> UIImage {
        guard let cgImage = image.cgImage else { return image }
        
        let width = cgImage.width
        let height = cgImage.height
        let bytesPerPixel = 4
        let bytesPerRow = width * bytesPerPixel
        let bitsPerComponent = 8
        
        guard let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: bitsPerComponent,
            bytesPerRow: bytesPerRow,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return image }
        
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        
        guard let data = context.data else { return image }
        let buffer = data.bindMemory(to: UInt8.self, capacity: width * height * bytesPerPixel)
        
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        
        // Find bounds of non-transparent pixels
        for y in 0..<height {
            for x in 0..<width {
                let pixelIndex = (y * width + x) * bytesPerPixel
                let alpha = buffer[pixelIndex + 3]
                
                // If pixel is not transparent
                if alpha > 0 {
                    if x < minX { minX = x }
                    if x > maxX { maxX = x }
                    if y < minY { minY = y }
                    if y > maxY { maxY = y }
                }
            }
        }
        
        // If no non-transparent pixels found, return a 1x1 transparent image
        guard maxX >= 0 && maxY >= 0 && minX <= maxX && minY <= maxY else {
            return UIImage()
        }
        
        // Calculate crop rect
        let cropRect = CGRect(x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1)
        
        // Crop the image
        guard let croppedCGImage = cgImage.cropping(to: cropRect) else { return image }
        
        return UIImage(cgImage: croppedCGImage, scale: image.scale, orientation: image.imageOrientation)
    }
    
    // Create mask using VNGenerateForegroundInstanceMaskRequest for any foreground objects
    @available(iOS 17.0, *)
    private func createMask(from inputImage: CIImage) -> CIImage? {
        let request = VNGenerateForegroundInstanceMaskRequest()
        let handler = VNImageRequestHandler(ciImage: inputImage)
        
        do {
            try handler.perform([request])
            
            if let result = request.results?.first {
                let mask = try result.generateScaledMaskForImage(forInstances: result.allInstances, from: handler)
                return CIImage(cvPixelBuffer: mask)
            }
        } catch {
            print("Error creating mask: \(error)")
        }
        
        return nil
    }
    
    // Apply mask to image using CIFilter.blendWithMask
    @available(iOS 17.0, *)
    private func applyMask(mask: CIImage, to image: CIImage) -> CIImage {
        let filter = CIFilter.blendWithMask()
        
        filter.inputImage = image
        filter.maskImage = mask
        filter.backgroundImage = CIImage.empty()
        
        return filter.outputImage ?? image
    }
    
    // Convert CIImage to UIImage
    @available(iOS 17.0, *)
    private func convertToUIImage(ciImage: CIImage) -> UIImage {
        let context = CIContext()
        guard let cgImage = context.createCGImage(ciImage, from: ciImage.extent) else {
            fatalError("Failed to render CGImage")
        }
        
        return UIImage(cgImage: cgImage)
    }
}
