# Compiled Android binary XML fixtures

`manifest-aapt2.axml` is the `AndroidManifest.xml` entry from an APK built by the
Android SDK Build Tools 34.0.0 `aapt2`. It uses the manifest's UTF-16 string pool.
`manifest-aapt2-utf8.axml` is the same source compiled as an ordinary XML resource,
which makes `aapt2` use a UTF-8 pool. No Android runtime, downloaded app, or generated
APK is required to run these JVM tests.

The checked-in source manifest and `res/values/strings.xml` intentionally exercise
Unicode, long UTF-8 lengths, aliases, namespaced attributes, booleans, signed
integers, flags, dimensions/fractions, and string resource references.

To regenerate, with `ANDROID_HOME` pointing to an Android SDK containing API 34:

```sh
fixture=app/src/test/resources/deeplinks
scratch=$(mktemp -d)
aapt2="$ANDROID_HOME/build-tools/34.0.0/aapt2"
framework="$ANDROID_HOME/platforms/android-34/android.jar"

"$aapt2" compile --dir "$fixture/res" -o "$scratch/resources.zip"
"$aapt2" link --manifest "$fixture/AndroidManifest.xml" -I "$framework" \
  -o "$scratch/fixture.apk" "$scratch/resources.zip"
unzip -p "$scratch/fixture.apk" AndroidManifest.xml > "$fixture/manifest-aapt2.axml"

mkdir -p "$scratch/res/xml" "$scratch/res/values"
cp "$fixture/AndroidManifest.xml" "$scratch/res/xml/deep_links.xml"
cp "$fixture/res/values/strings.xml" "$scratch/res/values/strings.xml"
"$aapt2" compile --dir "$scratch/res" -o "$scratch/utf8-resources.zip"
"$aapt2" link --manifest "$fixture/AndroidManifest.xml" -I "$framework" \
  -o "$scratch/utf8.apk" "$scratch/utf8-resources.zip"
unzip -p "$scratch/utf8.apk" res/xml/deep_links.xml > "$fixture/manifest-aapt2-utf8.axml"
```
