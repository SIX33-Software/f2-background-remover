# @six33/react-native-bg-removal

Universal background removal for React Native - removes backgrounds from any foreground objects using iOS 17+ Vision and Android MLKit Subject Segmentation with API fallback support.

<div align="center">
  <video src="https://github.com/user-attachments/assets/1f95fe30-5bce-463b-8d8b-5ca8fb5a0031" width="400" />
</div>



## ✨ Features

- **Universal Background Removal** - Works with any foreground objects (people, cars, objects, etc.)
- **iOS 17+ Vision Framework** - Native ML with `VNGenerateForegroundInstanceMaskRequest`
- **Android MLKit Subject Segmentation** - Google's powerful ML for object detection
- **API Fallback Support** - Graceful fallback for iOS 15.1-16.x versions
- **Auto-Cropping** - Automatically crops transparent pixels around detected objects
- **Performance Optimized** - Parallel processing for large images on multi-core devices
- **CPU-Aware Processing** - Adapts thread count based on device capabilities

## Installation

```sh
yarn add @six33/react-native-bg-removal
```

## Quick Start

```js
import { removeBackground } from '@six33/react-native-bg-removal';

try {
  // You can get the imageURI from the camera or gallery
  // By default, transparent pixels are trimmed
  const trimmedImageURI = await removeBackground(imageURI);
  
  // To disable trimming use:
  const untrimmedImageURI = await removeBackground(imageURI, { trim: false });

  console.log('Success:', trimmedImageURI);
} catch (error) {
  console.error('Background removal failed:', error.message);
}
```

## API Reference

### `removeBackground(imageURI: string, options?: RemovalOptions): Promise<string>`

Removes the background from an image and returns the processed image URI.

**Parameters:**
- `imageURI` (string): The URI of the image to process
- `options` (object, optional):
  - `trim` (boolean, default: `true`): If `true`, trims transparent pixels from the output image.

**Returns:**
- `Promise<string>`: URI of the processed image with background removed

**Throws:**
- `'REQUIRES_API_FALLBACK'`: On iOS 15.1-16.x (use external API)
- `Error`: For processing failures or invalid input

### `isNativeBackgroundRemovalSupported(): Promise<boolean>`

Checks if native background removal is supported on the current device.

**Returns:**
- `Promise<boolean>`: `true` if native ML is supported, `false` if API fallback is needed

## Advanced Usage with API Fallback

For iOS 15.1-16.x versions, you can implement a fallback to external APIs. The recommended approach is to check for native support first:

```js
import { removeBackground, isNativeBackgroundRemovalSupported } from '@six33/react-native-bg-removal';

async function processImage(imageURI) {
  const isNativeSupported = await isNativeBackgroundRemovalSupported();

  if (isNativeSupported) {
    // Use fast, native ML for background removal
    return await removeBackground(imageURI);
  } else {
    // Fallback to external API for older iOS versions
    return await removeBackgroundWithAPI(imageURI);
  }
}

async function removeBackgroundWithAPI(imageURI) {
  const formData = new FormData();
  formData.append('image', {
    uri: imageURI,
    type: 'image/jpeg',
    name: 'image.jpg',
  });

  try {
    const response = await fetch('https://[YOUR_BACKGROUND_REMOVAL_API]/remove-background', {
      method: 'POST',
      headers: {
        'X-Api-Key': 'YOUR_API_KEY',
        'Content-Type': 'multipart/form-data',
      },
      body: formData,
    });

    if (!response.ok) {
      throw new Error(`API Error: ${response.status}`);
    }

    const blob = await response.blob();
    
    // Convert blob to base64 for cleaner data handling
    return await convertBlobToBase64(blob);
  } catch (error) {
    console.error('API fallback failed:', error);
    throw new Error('Background removal failed on all methods');
  }
}

function convertBlobToBase64(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      resolve(reader.result); // Returns base64 data URI: "data:image/png;base64,..."
    };
    reader.onerror = reject;
    reader.readAsDataURL(blob);
  });
}
```

## Node.js Fallback API (Optional)

If you'd like to host your own fallback API in Node.js, you can use the `@imgly/background-removal-node` package. Here's a minimal example:

```js
// server.js
const express = require('express');
const multer = require('multer');
const os = require('os');
const fs = require('fs');
const { removeBackground } = require('@imgly/background-removal-node');

const upload = multer({ dest: os.tmpdir() });
const app = express();

app.post('/remove-background', upload.single('image'), async (req, res) => {
  if (!req.file) {
    return res.status(400).json({ error: 'No image file uploaded' });
  }
  const tempPath = req.file.path;
  try {
    const arrayBuffer = await removeBackground(tempPath);
    const imageBase64 = Buffer.from(imageBuffer).toString('base64');
    res.json({ imageBase64 });
  } catch (err) {
    res.status(500).json({ error: err.message });
  } finally {
    fs.unlink(tempPath, () => {}); // clean up temp file
  }
});

app.listen(3000, () =>
  console.log('Fallback API running on http://localhost:3000')
);
```

## Capability Detection

Check device capabilities before processing:

```js
import { isNativeBackgroundRemovalSupported } from '@six33/react-native-bg-removal';

async function checkCapabilities() {
  const isNativeSupported = await isNativeBackgroundRemovalSupported();
  
  if (isNativeSupported) {
    console.log('Native ML background removal supported');
  } else {
    console.log('Will require API fallback for background removal');
  }
}
```

## Platform-Specific Features

### iOS
- **iOS 17+**: Uses `VNGenerateForegroundInstanceMaskRequest` for universal object detection
- **iOS 15.1-16.x**: Throws `REQUIRES_API_FALLBACK` error for graceful API integration
- **Simulator**: Returns original image with warning (Vision requires real device)

### Android  
- **API 24+**: Uses MLKit Subject Segmentation for universal object detection
- **Emulator**: Fully supported (unlike iOS)
- **Performance**: Optimized with adaptive threading based on CPU cores

## Performance Features

### Auto-Cropping
Automatically removes transparent pixels around detected objects:
- Reduces output file size
- Focuses on the detected subject
- Maintains aspect ratio

### Parallel Processing
For large images (>1MP) on devices with 4+ CPU cores:
- **4 cores**: Optimized 4-quadrant processing
- **6+ cores**: Horizontal strip processing for better cache locality
- **<4 cores**: Sequential processing to avoid overhead

## Device Requirements

| Platform | Minimum Version | Features |
|----------|----------------|----------|
| **iOS** | 15.1+ | API fallback support |
| **iOS** | 17.0+ | Native universal background removal |
| **Android** | API 24+ | MLKit Subject Segmentation |

## Error Handling 
```js
import { removeBackground } from '@six33/react-native-bg-removal';

try {
  const result = await removeBackground(imageURI);
  console.log('Success:', result);
} catch (error) {
  switch (error.message) {
    case 'REQUIRES_API_FALLBACK':
      // Handle iOS 15.1-16.x fallback
      console.log('Using API fallback...');
      break;
    case 'Invalid URL':
      console.error('Please provide a valid image URI');
      break;
    case 'Unable to load image':
      console.error('Could not load the specified image');
      break;
    default:
      console.error('Unexpected error:', error.message);
  }
}
```

## Troubleshooting

### iOS Issues
- **Simulator not working**: Vision framework requires a real device
- **iOS 16 not working**: Expected behavior, implement API fallback
- **Slow processing**: Normal for large images, consider resizing input

### Android Issues  
- **App crashes on older devices**: Check minimum API level 24
- **MLKit model download**: Automatic via Google Play services
- **Performance**: Ensure sufficient device memory for large images

> **Note**: You need to use a real device on iOS to use this package. Vision framework requires actual device hardware. Android emulators are fully supported.

## Contributing

See the [contributing guide](CONTRIBUTING.md) to learn how to contribute to the repository and the development workflow.

## License

MIT

---

Made with [create-react-native-library](https://github.com/callstack/react-native-builder-bob)
