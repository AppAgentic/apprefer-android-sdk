# AppRefer Android sample — SDK 0.9.2

The sample uses the local `apprefer` and `apprefer-billing` modules at version 0.9.2.

```sh
./gradlew test :sample:assembleRelease
```

`AppRefer.updateConversionValue()` is available for API parity and does nothing on Android. SKAdNetwork and AdAttributionKit conversion values run on iOS only; see the root README for the iOS setup and postback-copy Info.plist keys.
