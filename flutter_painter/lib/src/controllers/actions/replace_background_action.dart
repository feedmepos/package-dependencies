import 'package:flutter/foundation.dart';
import 'package:flutter_painter/flutter_painter.dart';

import 'action.dart';

class ReplaceBackgroundDrawableAction extends ControllerAction<void, void> {
  final BackgroundDrawable newBackgroundDrawable;

  final BackgroundDrawable? oldBackgroundDrawable;

  ReplaceBackgroundDrawableAction(
    this.oldBackgroundDrawable,
    this.newBackgroundDrawable,
  );

  /// Performs the action.
  @protected
  @override
  void perform$(PainterController controller) {
    final value = controller.value;
    controller.value = value.copyWith(background: newBackgroundDrawable);
  }

  /// Un-performs the action.
  @protected
  @override
  void unperform$(PainterController controller) {
    final value = controller.value;
    controller.value = value.copyWith(
      background: oldBackgroundDrawable,
    );
  }
}
