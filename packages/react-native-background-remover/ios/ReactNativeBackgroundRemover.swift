import Vision
import CoreImage
import CoreImage.CIFilterBuiltins

public class BackgroundRemoverSwift: NSObject {
    
    @objc
    public func removeBackground(_ imageURI: String, options: [String: Any], resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) -> Void {
        #if targetEnvironment(simulator)
        reject("BackgroundRemover", "SimulatorError", NSError(domain: "BackgroundRemover", code: 2))
        return
        #endif

        if #available(iOS 17.0, *) {
            let trim = options["trim"] as? Bool ?? true
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

                    let finalImage = if trim {
                        // Trim transparent pixels around the image
                        self.trimTransparentPixels(from: uiImage)
                    } else {
                        uiImage
                    }
                    
                    // Save the image as PNG to preserve transparency
                    let tempURL = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(url.lastPathComponent).appendingPathExtension("png")
                    if let data = finalImage.pngData() {
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
    
    // Trim transparent pixels around the image
    private func trimTransparentPixels(from image: UIImage) -> UIImage {
        guard let cgImage = image.cgImage else { return image }
        
        let totalPixels = cgImage.width * cgImage.height
        let coreCount = ProcessInfo.processInfo.processorCount
        
        return (totalPixels > 1000000 && coreCount >= 4) ? 
            trimTransparentPixelsParallel(from: image) : 
            trimTransparentPixelsSequential(from: image)
    }
    
    private func trimTransparentPixelsSequential(from image: UIImage) -> UIImage {
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
    
    // Parallel implementation for large images
    private func trimTransparentPixelsParallel(from image: UIImage) -> UIImage {
        guard let cgImage = image.cgImage else { return image }
        
        let width = cgImage.width
        let height = cgImage.height
        let coreCount = ProcessInfo.processInfo.processorCount
        
        // Setup shared context for pixel data access
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
        
        // Use optimal number of threads based on available cores (min 4, max 8)
        let threadCount = min(max(coreCount, 4), 8)
        
        // Split into optimal number of regions based on thread count
        let quadrants: [QuadrantRect]
        if threadCount == 4 {
            // Classic 4-quadrant split for 4 cores
            let halfWidth = width / 2
            let halfHeight = height / 2
            quadrants = [
                QuadrantRect(startX: 0, startY: 0, endX: halfWidth, endY: halfHeight),           // Top-left
                QuadrantRect(startX: halfWidth, startY: 0, endX: width, endY: halfHeight),      // Top-right (gets extra width if odd)
                QuadrantRect(startX: 0, startY: halfHeight, endX: halfWidth, endY: height),     // Bottom-left (gets extra height if odd)
                QuadrantRect(startX: halfWidth, startY: halfHeight, endX: width, endY: height)  // Bottom-right (gets both extras if odd)
            ]
        } else {
            // For more cores, split into horizontal strips for better cache locality
            let stripHeight = height / threadCount
            quadrants = (0..<threadCount).map { i in
                let startY = i * stripHeight
                let endY = (i == threadCount - 1) ? height : (i + 1) * stripHeight
                return QuadrantRect(startX: 0, startY: startY, endX: width, endY: endY)
            }
        }
        
        // Process regions concurrently using GCD
        let group = DispatchGroup()
        let queue = DispatchQueue(label: "crop.parallel", attributes: .concurrent)
        var results: [QuadrantBounds] = Array(repeating: QuadrantBounds(), count: threadCount)
        
        for (index, quadrant) in quadrants.enumerated() {
            group.enter()
            queue.async {
                results[index] = self.findBoundsInQuadrant(buffer: buffer, quadrant: quadrant, width: width, bytesPerPixel: bytesPerPixel)
                group.leave()
            }
        }
        
        group.wait()
        
        let globalBounds = mergeBounds(results: results)
        
        guard globalBounds.isValid else { 
            return UIImage() // Return empty image if no non-transparent pixels found
        }
        
        let cropRect = CGRect(x: globalBounds.minX, y: globalBounds.minY, 
                             width: globalBounds.width, height: globalBounds.height)
        
        guard let croppedCGImage = cgImage.cropping(to: cropRect) else { return image }
        
        return UIImage(cgImage: croppedCGImage, scale: image.scale, orientation: image.imageOrientation)
    }
    
    private struct QuadrantRect {
        let startX: Int
        let startY: Int
        let endX: Int
        let endY: Int
    }
    
    private struct QuadrantBounds {
        var minX: Int = Int.max
        var minY: Int = Int.max
        var maxX: Int = -1
        var maxY: Int = -1
        
        var isValid: Bool { maxX >= 0 && maxY >= 0 }
        var width: Int { maxX - minX + 1 }
        var height: Int { maxY - minY + 1 }
    }
    
    private func findBoundsInQuadrant(buffer: UnsafeMutablePointer<UInt8>, quadrant: QuadrantRect, width: Int, bytesPerPixel: Int) -> QuadrantBounds {
        var bounds = QuadrantBounds()
        
        // Scan only the pixels in this quadrant
        for y in quadrant.startY..<quadrant.endY {
            for x in quadrant.startX..<quadrant.endX {
                let pixelIndex = (y * width + x) * bytesPerPixel
                let alpha = buffer[pixelIndex + 3]
                
                // If pixel is not transparent
                if alpha > 0 {
                    if x < bounds.minX { bounds.minX = x }
                    if x > bounds.maxX { bounds.maxX = x }
                    if y < bounds.minY { bounds.minY = y }
                    if y > bounds.maxY { bounds.maxY = y }
                }
            }
        }
        
        return bounds
    }
    
    private func mergeBounds(results: [QuadrantBounds]) -> QuadrantBounds {
        let validResults = results.filter { $0.isValid }
        
        guard !validResults.isEmpty else {
            return QuadrantBounds() // Invalid bounds
        }
        
        var merged = QuadrantBounds()
        merged.minX = validResults.map { $0.minX }.min() ?? Int.max
        merged.minY = validResults.map { $0.minY }.min() ?? Int.max
        merged.maxX = validResults.map { $0.maxX }.max() ?? -1
        merged.maxY = validResults.map { $0.maxY }.max() ?? -1
        
        return merged
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
