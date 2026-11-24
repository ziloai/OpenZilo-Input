# OpenZilo 输入法

[English](README.md)

按住 OpenZilo 戒指的按键说话，就能在手机上打字。打开备忘录、聊天等应用，切到 OpenZilo 输入法；说完松手，识别结果就会插入光标所在的位置。输入法只填文字，不会替你发送消息。

<a href="docs/assets/ring-dictation.mp4"><img src="docs/assets/ring-dictation.gif" alt="OpenZilo 输入法接收戒指语音，并把识别结果插入备忘录" width="300"></a>

[打开 9 秒有声演示（MP4）](docs/assets/ring-dictation.mp4)

## 快速开始

你需要一枚 OpenZilo 戒指和一部运行 Android 13 或更高版本的手机。从下面两个发布页分别下载 `-debug.apk` 安装试用；未签名的 AAB 不能直接安装。

1. 安装 [OpenZilo Connect](https://github.com/ziloai/OpenZilo-Connect/releases/latest)，在 Connect 里连接戒指。
2. 安装 [OpenZilo 输入法](https://github.com/ziloai/OpenZilo-Input/releases/latest)，打开后点「授权」，允许输入法通过 Connect 接收戒指音频。
3. 启用并选择 OpenZilo 输入法，在「语音识别」里下载本地模型（约 418 MiB），等到显示「已就绪」。
4. 点开输入法自带的测试框，或其他应用里的文本框。输入面板出现后，按住戒指按键说话，松开就能插入文字。单次最长 60 秒。

## 识别与隐私

- 中文和英语支持边说边出字，最终识别还支持日语、韩语和粤语。
- 松手后，输入法会对整段录音再识别一次，去掉一些常见的语气词，并补上句末标点。
- Connect 负责提供戒指音频。首次使用需要联网下载模型，之后语音在手机本地识别，不上传音频，也不会回退到云端识别。
- 只有输入面板显示在可用的文本框时，输入法才接收戒指音频。密码等私密输入框禁用语音输入，输入法不保留音频或转写历史。

## 从源码构建

使用与 CI 一致的 JDK 21，确保 `python3` 可用，并安装 Android SDK Platform `android-37.0` 和 Build Tools `37.0.0`。

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。Gradle 在构建时下载 sherpa-onnx 运行库；语音模型由应用另行下载。

## 许可证

OpenZilo 输入法的源代码采用 [Mozilla Public License 2.0](LICENSE)。第三方组件和模型沿用各自的许可证，包括 [sherpa-onnx 运行库的许可证](app/src/main/assets/sherpa-onnx/LICENSE)。
