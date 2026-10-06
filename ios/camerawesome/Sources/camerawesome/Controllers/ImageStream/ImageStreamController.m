//
//  ImageStreamController.m
//  camerawesome
//
//  Created by Dimitri Dessus on 17/12/2020.
//

#import "ImageStreamController.h"
#import <Accelerate/Accelerate.h>

@implementation ImageStreamController

NSInteger const MaxPendingProcessedImage = 4;

- (instancetype)initWithStreamImages:(bool)streamImages {
  self = [super init];
  _streamImages = streamImages;
  _processingImage = 0;
  return self;
}

# pragma mark - Camera Delegates
- (void)captureOutput:(AVCaptureOutput *)output didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer fromConnection:(AVCaptureConnection *)connection orientation:(UIDeviceOrientation)orientation {
  if (_imageStreamEventSink == nil) {
    return;
  }
  
  bool shouldFPSGuard = [self fpsGuard];
  bool shouldOverflowCrashingGuard = [self overflowCrashingGuard];
  
  if (shouldFPSGuard || shouldOverflowCrashingGuard) {
    return;
  }
  
  _processingImage++;
  
  // The session can run at the recording size (up to 4K) while the stream
  // only feeds a small remote preview: downscale off the main thread so
  // each emitted frame stays at most kMaxStreamLongSide on its long side -
  // the 720p-class frames this stream always carried.
  CVPixelBufferRef pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer);
  CVPixelBufferRetain(pixelBuffer);
  NSString *rotation = [self getInputImageOrientation:orientation];
  dispatch_async([ImageStreamController scaleQueue], ^{
    NSDictionary *imageBuffer = [ImageStreamController streamFrameFrom:pixelBuffer rotation:rotation];
    CVPixelBufferRelease(pixelBuffer);
    dispatch_async(dispatch_get_main_queue(), ^{
      if (imageBuffer == nil || self->_imageStreamEventSink == nil) {
        // Nothing reaches Dart, so no ack will come: release the slot here.
        [self receivedImageFromStream];
        return;
      }
      self->_imageStreamEventSink(imageBuffer);
    });
  });
}

static const size_t kMaxStreamLongSide = 1280;

+ (dispatch_queue_t)scaleQueue {
  static dispatch_queue_t queue;
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    queue = dispatch_queue_create("camerawesome.imagestream.scale", DISPATCH_QUEUE_SERIAL);
  });
  return queue;
}

/// One bgra8888 plane, downscaled when the source is larger than
/// kMaxStreamLongSide; same map shape the stream always emitted.
+ (nullable NSDictionary *)streamFrameFrom:(CVPixelBufferRef)pixelBuffer rotation:(NSString *)rotation {
  if (CVPixelBufferIsPlanar(pixelBuffer)) {
    return nil; // the data output is configured for 32BGRA
  }
  CVPixelBufferLockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);
  size_t srcWidth = CVPixelBufferGetWidth(pixelBuffer);
  size_t srcHeight = CVPixelBufferGetHeight(pixelBuffer);
  size_t longSide = MAX(srcWidth, srcHeight);
  
  size_t width = srcWidth;
  size_t height = srcHeight;
  size_t bytesPerRow;
  NSData *bytes;
  if (longSide > kMaxStreamLongSide) {
    width = srcWidth * kMaxStreamLongSide / longSide;
    height = srcHeight * kMaxStreamLongSide / longSide;
    bytesPerRow = width * 4;
    NSMutableData *scaled = [NSMutableData dataWithLength:bytesPerRow * height];
    vImage_Buffer src = {
      .data = CVPixelBufferGetBaseAddress(pixelBuffer),
      .height = srcHeight,
      .width = srcWidth,
      .rowBytes = CVPixelBufferGetBytesPerRow(pixelBuffer),
    };
    vImage_Buffer dst = {
      .data = scaled.mutableBytes,
      .height = height,
      .width = width,
      .rowBytes = bytesPerRow,
    };
    vImage_Error err = vImageScale_ARGB8888(&src, &dst, NULL, kvImageNoFlags);
    CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);
    if (err != kvImageNoError) {
      return nil;
    }
    bytes = scaled;
  } else {
    bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer);
    bytes = [NSData dataWithBytes:CVPixelBufferGetBaseAddress(pixelBuffer) length:bytesPerRow * srcHeight];
    CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);
  }
  
  return @{
    @"width": @(width),
    @"height": @(height),
    @"format": @"bgra8888",
    @"planes": @[@{
      @"bytesPerRow": @(bytesPerRow),
      @"width": @(width),
      @"height": @(height),
      @"bytes": [FlutterStandardTypedData typedDataWithBytes:bytes],
    }],
    @"rotation": rotation,
  };
}

- (NSString *)getInputImageOrientation:(UIDeviceOrientation)orientation {
  switch (orientation) {
    case UIDeviceOrientationLandscapeLeft:
      return @"rotation90deg";
    case UIDeviceOrientationLandscapeRight:
      return @"rotation270deg";
    case UIDeviceOrientationPortrait:
      return @"rotation0deg";
    case UIDeviceOrientationPortraitUpsideDown:
      return @"rotation180deg";
    default:
      return @"rotation0deg";
  }
}

#pragma mark - Guards

- (bool)fpsGuard {
  // calculate time interval between latest emitted frame
  NSDate *nowDate = [NSDate date];
  NSTimeInterval secondsBetween = [nowDate timeIntervalSinceDate:_latestEmittedFrame];
  
  // fps limit check, ignored if nil or == 0
  if (_maxFramesPerSecond && _maxFramesPerSecond > 0) {
    if (secondsBetween <= (1 / _maxFramesPerSecond)) {
      // skip image because out of time
      return YES;
    }
  }
  
  return NO;
}

- (bool)overflowCrashingGuard {
  // overflow crash prevent condition
  if (_processingImage > MaxPendingProcessedImage) {
    // too many frame are pending processing, skipping...
    // this prevent crashing on older phones like iPhone 6, 7...
    return YES;
  }
  
  return NO;
}

// This is used to know the exact time when the image was received on the Flutter part
- (void)receivedImageFromStream {
  // used for the fps limit condition
  _latestEmittedFrame = [NSDate date];
  
  // used for the overflow prevent crashing condition
  if (_processingImage >= 0) {
    _processingImage--;
  }
}

#pragma mark - Setters

- (void)setImageStreamEventSink:(FlutterEventSink)imageStreamEventSink {
  _imageStreamEventSink = imageStreamEventSink;
}

- (void)setMaxFramesPerSecond:(float)maxFramesPerSecond {
  _maxFramesPerSecond = maxFramesPerSecond;
}

- (void)setStreamImages:(bool)streamImages {
  _streamImages = streamImages;
}

@end
