import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export interface RemovalOptions {
  trim?: boolean;
}

export interface Spec extends TurboModule {
  removeBackground(
    imageURI: string,
    options: RemovalOptions
  ): Promise<string>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('BackgroundRemover');
