#import "ReactNativeBackgroundRemover.h"
// Conditional import for the Swift header file
#if __has_include("ReactNativeBackgroundRemover-Swift.h")
#import "ReactNativeBackgroundRemover-Swift.h"
#elif __has_include("ReactNativeBackgroundRemover/ReactNativeBackgroundRemover-Swift.h")
#import "ReactNativeBackgroundRemover/ReactNativeBackgroundRemover-Swift.h"
#else
#error "ReactNativeBackgroundRemover-Swift.h not found"
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

RCT_EXPORT_METHOD(removeBackground:(NSString *)imageURI
                 withOptions:(NSDictionary *)options
                 resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
    [backgroundRemover removeBackground:imageURI options:options resolve:resolve reject:reject];
}

// Don't compile this code when we build for the old architecture.
#ifdef RCT_NEW_ARCH_ENABLED
- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
    return std::make_shared<facebook::react::NativeBackgroundRemoverSpecJSI>(params);
}
#endif

@end
