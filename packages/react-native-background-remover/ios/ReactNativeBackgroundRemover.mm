#import "ReactNativeBackgroundRemover.h"
// Conditional import for the Swift header file
#if __has_include("ReactNativeBackgroundRemover-Swift.h")
#import "ReactNativeBackgroundRemover-Swift.h"
#elif __has_include("ReactNativeBackgroundRemover/ReactNativeBackgroundRemover-Swift.h")
#import "ReactNativeBackgroundRemover/ReactNativeBackgroundRemover-Swift.h"
#else
#error "ReactNativeBackgroundRemover-Swift.h not found"
#endif

#ifdef RCT_NEW_ARCH_ENABLED
#import "RNBackgroundRemoverSpec.h"
#endif

@implementation BackgroundRemover {
  BackgroundRemoverSwift *backgroundRemover;
}

RCT_EXPORT_MODULE()

- (id)init {
    self = [super init];
 
    if (self) {
        backgroundRemover = [[BackgroundRemoverSwift alloc] init];
    }
 
    return self;
}

#ifdef RCT_NEW_ARCH_ENABLED
// New Architecture (TurboModule)
- (void)removeBackground:(NSString *)imageURI
                 options:(JS::NativeBackgroundRemover::RemovalOptions &)options
                 resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject
{
    BOOL trimValue = options.trim().has_value() ? options.trim().value() : YES;

    NSDictionary *optionsDict = @{
        @"trim": @(trimValue)
    };
    
    [backgroundRemover removeBackground:imageURI options:optionsDict resolve:resolve reject:reject];
}

// Register the TurboModule
- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
    return std::make_shared<facebook::react::NativeBackgroundRemoverSpecJSI>(params);
}

#else
// Old Architecture (Bridge)
RCT_EXPORT_METHOD(removeBackground:(NSString *)imageURI
                 withOptions:(NSDictionary *)options
                 resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
    [backgroundRemover removeBackground:imageURI options:options resolve:resolve reject:reject];
}
#endif

@end