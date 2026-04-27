// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'inactive_scheduling_policy.dart';

// **************************************************************************
// ExchangeableEnumGenerator
// **************************************************************************

///Class that represents policies for how a web view that's not in a window handles tasks.
class InactiveSchedulingPolicy {
  final int _value;
  final int? _nativeValue;
  const InactiveSchedulingPolicy._internal(this._value, this._nativeValue);
  // ignore: unused_element
  factory InactiveSchedulingPolicy._internalMultiPlatform(
    int value,
    Function nativeValue,
  ) => InactiveSchedulingPolicy._internal(value, nativeValue());

  ///A policy where a web view that's not in a window runs tasks normally.
  static const NONE = InactiveSchedulingPolicy._internal(2, 2);

  ///A policy where a web view that's not in a window fully suspends tasks.
  static const SUSPEND = InactiveSchedulingPolicy._internal(0, 0);

  ///A policy where a web view that's not in a window limits processing, but does not fully suspend tasks.
  static const THROTTLE = InactiveSchedulingPolicy._internal(1, 1);

  ///Set of all values of [InactiveSchedulingPolicy].
  static final Set<InactiveSchedulingPolicy> values = [
    InactiveSchedulingPolicy.NONE,
    InactiveSchedulingPolicy.SUSPEND,
    InactiveSchedulingPolicy.THROTTLE,
  ].toSet();

  ///Gets a possible [InactiveSchedulingPolicy] instance from [int] value.
  static InactiveSchedulingPolicy? fromValue(int? value) {
    if (value != null) {
      try {
        return InactiveSchedulingPolicy.values.firstWhere(
          (element) => element.toValue() == value,
        );
      } catch (e) {
        return null;
      }
    }
    return null;
  }

  ///Gets a possible [InactiveSchedulingPolicy] instance from a native value.
  static InactiveSchedulingPolicy? fromNativeValue(int? value) {
    if (value != null) {
      try {
        return InactiveSchedulingPolicy.values.firstWhere(
          (element) => element.toNativeValue() == value,
        );
      } catch (e) {
        return null;
      }
    }
    return null;
  }

  /// Gets a possible [InactiveSchedulingPolicy] instance value with name [name].
  ///
  /// Goes through [InactiveSchedulingPolicy.values] looking for a value with
  /// name [name], as reported by [InactiveSchedulingPolicy.name].
  /// Returns the first value with the given name, otherwise `null`.
  static InactiveSchedulingPolicy? byName(String? name) {
    if (name != null) {
      try {
        return InactiveSchedulingPolicy.values.firstWhere(
          (element) => element.name() == name,
        );
      } catch (e) {
        return null;
      }
    }
    return null;
  }

  /// Creates a map from the names of [InactiveSchedulingPolicy] values to the values.
  ///
  /// The collection that this method is called on is expected to have
  /// values with distinct names, like the `values` list of an enum class.
  /// Only one value for each name can occur in the created map,
  /// so if two or more values have the same name (either being the
  /// same value, or being values of different enum type), at most one of
  /// them will be represented in the returned map.
  static Map<String, InactiveSchedulingPolicy> asNameMap() =>
      <String, InactiveSchedulingPolicy>{
        for (final value in InactiveSchedulingPolicy.values)
          value.name(): value,
      };

  ///Gets [int] value.
  int toValue() => _value;

  ///Gets [int] native value if supported by the current platform, otherwise `null`.
  int? toNativeValue() => _nativeValue;

  ///Gets the name of the value.
  String name() {
    switch (_value) {
      case 2:
        return 'NONE';
      case 0:
        return 'SUSPEND';
      case 1:
        return 'THROTTLE';
    }
    return _value.toString();
  }

  @override
  int get hashCode => _value.hashCode;

  @override
  bool operator ==(value) => value == _value;

  ///Checks if the value is supported by the [defaultTargetPlatform].
  bool isSupported() {
    return _nativeValue != null;
  }

  @override
  String toString() {
    return name();
  }
}
