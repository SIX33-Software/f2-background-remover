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
                    
                    // Save the image as PNG to preserve transparency
                    let tempURL = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(url.lastPathComponent).appendingPathExtension("png")
                    if let data = uiImage.pngData() {
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
            reject("BackgroundRemover", "You need a device with iOS 17 or later", NSError(domain: "BackgroundRemover", code: 1))
        }
    }
    
    // Create mask using VNGenerateForegroundInstanceMaskRequest for any foreground objects
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
    private func applyMask(mask: CIImage, to image: CIImage) -> CIImage {
        let filter = CIFilter.blendWithMask()
        
        filter.inputImage = image
        filter.maskImage = mask
        filter.backgroundImage = CIImage.empty()
        
        return filter.outputImage ?? image
    }
    
    // Convert CIImage to UIImage
    private func convertToUIImage(ciImage: CIImage) -> UIImage {
        let context = CIContext()
        guard let cgImage = context.createCGImage(ciImage, from: ciImage.extent) else {
            fatalError("Failed to render CGImage")
        }
        
        return UIImage(cgImage: cgImage)
    }
}
