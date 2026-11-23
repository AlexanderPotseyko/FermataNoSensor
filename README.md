## !!!Be aware of scam!!! The sites like https :// fermata-auto . com  are 100% scam. This app is free and opensource!


## FermataNoSensor

This repository is a modified fork of [Fermata](https://github.com/AndreyPavlenko/Fermata) by Andrey Pavlenko, released under the GNU GPL-3.0 (see `LICENSE`). The original author credit is unchanged.

FermataNoSensor is a personal Android Auto build for YouTube only. It installs side by side with the original Fermata (`applicationId` `by.potseyko.fermatanosensor`, launcher name FermataNoSensor). IPTV, remote libraries, Cast, chat, mirror-mode launcher entries and the other unused add-ons are left out of this build. ExoPlayer, MediaPlayer and VLC, including the hardware-decoding setting, stay available.

The release signing key is in `signing/` (see `signing/README.md`). It is committed on purpose so later personal builds update the same install. Do not publish this keystore.

The launcher icon is the original mark on a red background with a yellow ring, including the adaptive icon and the Android Auto media icon.

On the YouTube page the rotary knob (DPAD up/down or `SOURCE_ROTARY_ENCODER` scroll) moves a yellow focus outline and scrolls the item into view. The first downward step prefers the search field; center opens the car keyboard. Center clicks other items. Left at the left edge moves focus to the Fermata bar. In the full-screen player, center pauses or plays, rotation seeks by 10 seconds, and back leaves the player.

Settings on the phone (Шайба) record those events to `knob.log` (about 1 MB) and can share or clear the file. Pointer drags are not stored. Each line includes the YouTube mode.

Settings → YouTube → «Режим YouTube» defaults to «Веб-страница». «ТВ-режим (эксперимент)» loads `https://www.youtube.com/tv` with the VacuumTube Cobalt user agent. Cookies stay in the app WebView. Sign in from the code on that page at `https://www.youtube.com/activate` in another browser. «Поворот шайбы в ТВ-режиме» defaults to up/down. If `/tv` redirects away, that visit falls back to the web page. Web mode keeps the yellow focus outline.


## Fermata Media Player
[![Download the latest APK](https://img.shields.io/github/v/release/AndreyPavlenko/Fermata?label=Download%20APK&logo=github&style=for-the-badge)](https://github.com/AndreyPavlenko/Fermata/releases/latest)

## About
Fermata Media Player is a free, open source audio, video and TV player with a simple and intuitive interface. It is focused on playing media files organized in folders and playlists.

Supported features:

* Play media files organized in folders
* IPTV addon with support for XMLTV EPG and Catchup
* Remembers the last played track and position for each folder
* Support for favorites and playlists
* Support for CUE and M3U playlists
* Support for bookmarks
* Audio effects: Equalizer, Bass/Volume Boost and Virtualizer
* Configure audio effects for individual tracks and folders
* Configure playback speed for individual tracks and folders
* Customizable titles and subtitles
* Support for Android Auto
* Support for Android TV
* Show favorites and playlists on Android TV home screen
* Pluggable media engines: MediaPlayer, ExoPlayer and VLC
* Video player with support for subtitles and audio streams (VLC Engine only)

## Building the project
* Download and install the latest Android SDK or Android Studio from https://developer.android.com/studio/
* Set the environment variable ANDROID_SDK_ROOT pointing to the SDK directory
```bash
export ANDROID_SDK_ROOT=<path to android SDK>
```

### Clone the repository
```bash
git clone --recurse-submodules https://github.com/AndreyPavlenko/Fermata.git
cd Fermata
```

### Build AAB
```bash
./gradlew bundleAutoRelease -PAPP_ID_SFX=.type.your.pkg.sfx.here
find $PWD -name *.aab
```

### Build APK
```bash
./gradlew bundleAutoRelease -PAPP_ID_SFX=.type.your.pkg.sfx.here
find $PWD -name *.apk
```

### Building in docker
```bash
docker run -ti --name Fermata andreypavlenko/fermata
```
Enter the requested key alias and password, when prompted.
Build the required package using the above commands.
To copy the built package to the host machine, open a new terminal and run:
```bash
docker cp Fermata:/home/mobiledevops/Fermata/fermata/build/outputs/bundle/autoRelease/ .
```


## Donation
If you like the application, please consider making a donation:

[PayPal](https://www.paypal.com/donate/?hosted_button_id=NP5Q3YDSCJ98N)

[CloudTips](https://pay.cloudtips.ru/p/a03a73da)

[Yandex Money](https://money.yandex.ru/to/410014661137336)
