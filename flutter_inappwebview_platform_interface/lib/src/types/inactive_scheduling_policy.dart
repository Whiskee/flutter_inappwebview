import 'package:flutter_inappwebview_internal_annotations/flutter_inappwebview_internal_annotations.dart';

part 'inactive_scheduling_policy.g.dart';

///Class that represents policies for how a web view that's not in a window handles tasks.
@ExchangeableEnum()
class InactiveSchedulingPolicy_ {
  // ignore: unused_field
  final int _value;
  const InactiveSchedulingPolicy_._internal(this._value);

  ///A policy where a web view that's not in a window fully suspends tasks.
  static const SUSPEND = const InactiveSchedulingPolicy_._internal(0);

  ///A policy where a web view that's not in a window limits processing, but does not fully suspend tasks.
  static const THROTTLE = const InactiveSchedulingPolicy_._internal(1);

  ///A policy where a web view that's not in a window runs tasks normally.
  static const NONE = const InactiveSchedulingPolicy_._internal(2);
}
