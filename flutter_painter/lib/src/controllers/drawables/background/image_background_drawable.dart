import 'dart:ui';

import 'package:flutter/material.dart' as mt;

import 'background_drawable.dart';

/// Drawable to use an image as a background.
@mt.immutable
class ImageBackgroundDrawable extends BackgroundDrawable {
  /// The image to be used as a background.
  final Image image;

  final double? aspectRatio;

  final double blurRadius;

  final ColorFilter? colorFilter;

  /// Creates a [ImageBackgroundDrawable] to use an image as a background.
  const ImageBackgroundDrawable({
    required this.image,
    this.aspectRatio,
    this.blurRadius = 0,
    this.colorFilter,
  });

  double get imageWidth => image.width.toDouble();
  double get imageHeight => image.height.toDouble();

  Size getImageSize() {
    if (aspectRatio == null) {
      return Size(imageWidth, imageHeight);
    }

    double imageAspectRatio = imageWidth / imageHeight;

    final newAspectRatio = aspectRatio ?? imageAspectRatio;

    // Get new image size from new aspect ratio.
    double newWidth;
    double newHeight;
    // If image is wider than the desired aspect ratio, then crop extra width
    if (imageAspectRatio > newAspectRatio) {
      newWidth = imageHeight * newAspectRatio;
      newHeight = imageHeight;
    } else {
      // If image is taller than the desired aspect ratio, then crop extra height
      newWidth = imageWidth;
      newHeight = imageWidth / newAspectRatio;
    }

    return Size(newWidth, newHeight);
  }

  /// Construct a new image rect based on the new aspect ratio.
  Rect getNewImageRect({required double newAspectRatio}) {
    final newImageSize = getImageSize();

    // Calculate the offset to center the image
    double x = (imageWidth - newImageSize.width) / 2;
    double y = (imageHeight - newImageSize.height) / 2;

    return Rect.fromLTWH(x, y, newImageSize.width, newImageSize.height);
  }

  /// Draws the image on the provided [canvas] of size [size].
  @override
  void draw(Canvas canvas, Size size) {
    var imageRect = Rect.fromPoints(
      Offset.zero,
      Offset(image.width.toDouble(), image.height.toDouble()),
    );
    if (aspectRatio case final double newAspectRatio) {
      imageRect = getNewImageRect(newAspectRatio: newAspectRatio);
    }

    canvas.drawImageRect(
      image,
      imageRect,
      Rect.fromPoints(Offset.zero, Offset(size.width, size.height)),
      Paint()
        ..imageFilter = ImageFilter.blur(
          sigmaX: blurRadius,
          sigmaY: blurRadius,
        )
        ..colorFilter = colorFilter,
    );
  }

  // /// Compares two [ImageBackgroundDrawable]s for equality.
  // @override
  // bool operator ==(Object other) {
  //   return other is ImageBackgroundDrawable && other.image == image;
  // }
  //
  // @override
  // int get hashCode => image.hashCode;

  ImageBackgroundDrawable copyWith({
    Image? image,
    double? aspectRatio,
    double? blurRadius,
    ColorFilter? Function()? colorFilter,
  }) {
    return ImageBackgroundDrawable(
      image: image ?? this.image,
      aspectRatio: aspectRatio ?? this.aspectRatio,
      blurRadius: blurRadius ?? this.blurRadius,
      colorFilter: colorFilter != null ? colorFilter() : this.colorFilter,
    );
  }
}

/// An extension on ui.Image to create a background drawable easily.
extension ImageBackgroundDrawableGetter on Image {
  /// Returns an [ImageBackgroundDrawable] of the current [Image].
  ImageBackgroundDrawable backgroundDrawable({double? aspectRatio}) =>
      ImageBackgroundDrawable(image: this, aspectRatio: aspectRatio);
}
