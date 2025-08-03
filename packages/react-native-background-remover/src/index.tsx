import { NativeModules, Platform } from 'react-native';

const LINKING_ERROR =
  `The package 'react-native-background-remover' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';

// @ts-ignore
const isTurboModuleEnabled = global.__turboModuleProxy != null;

const BackgroundRemoverModule = isTurboModuleEnabled
  ? require('./NativeBackgroundRemover').default
  : NativeModules.BackgroundRemover;

const BackgroundRemover = BackgroundRemoverModule
  ? BackgroundRemoverModule
  : new Proxy(
      {},
      {
        get() {
          throw new Error(LINKING_ERROR);
        },
      }
    );

/**
 * Removes the background from an image.
 * Note: This method isn't usable on iOS simulators, you need to have a real device.
 * On iOS < 17.0, this will throw a 'REQUIRES_API_FALLBACK' error for you to handle with your API.
 * @returns The URI of the image with the background removed.
 * @returns the original URI if you are using an iOS simulator.
 * @throws Error with message 'REQUIRES_API_FALLBACK' if iOS < 17.0 (use API fallback).
 * @throws Error if the image could not be processed for an unknown reason.
 */
export async function removeBackground(imageURI: string): Promise<string> {
  try {
    const result: string = await BackgroundRemover.removeBackground(imageURI);
    return result;
  } catch (error) {
    if (error instanceof Error && error.message === 'SimulatorError') {
      console.warn(
        '[ReactNativeBackgroundRemover]: You need to have a real device. This feature is not available on simulators. Returning the original image URI.'
      );
      return imageURI;
    }

    // Re-throw REQUIRES_API_FALLBACK errors for the app to handle
    if (error instanceof Error && error.message === 'REQUIRES_API_FALLBACK') {
      throw error;
    }

    throw error;
  }
}

/**
 * Check if native background removal is supported on this device.
 * @returns true if native ML is supported, false if API fallback is needed
 */
export async function isNativeBackgroundRemovalSupported(): Promise<boolean> {
  try {
    // Try with a dummy/test image to check capability
    await BackgroundRemover.removeBackground('test://capability-check');
    return true;
  } catch (error) {
    if (error instanceof Error && error.message === 'REQUIRES_API_FALLBACK') {
      return false;
    }
    // For other errors (like invalid URL), assume native is supported
    return true;
  }
}
