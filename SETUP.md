# Windows 环境搭建（可选）

本项目在 Windows + Android Studio 下开发。下面是环境搭建记录，照做可以跑起 `gradlew build`
并装到模拟器 / 真机上。里面的路径都是示例，按自己的习惯替换即可。

## 组件清单

| 组件 | 说明 |
|---|---|
| JDK | **21**。AGP 8.13 / Gradle 8.14 需要 17+；Android Studio 自带的 JBR 版本通常过新，不建议用它构建 |
| Android SDK | 含 `platform-tools`(adb)、`platforms;android-35`、`build-tools;36.0.0`、`emulator` |
| Gradle | 用仓库里的 wrapper 就行，不必单独安装 |
| 模拟器镜像 | `system-images;android-35;google_apis;x86_64`（x86_64 镜像需要开启 Hyper-V / WHPX） |

## 1. JDK 21

- Temurin（推荐）：https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse
- Oracle：https://download.oracle.com/java/21/latest/jdk-21_windows-x64_bin.zip
- 华为云 OpenJDK 镜像：https://mirrors.huaweicloud.com/openjdk/21.0.2/

解压到某个纯 ASCII 路径（例如 `D:\jdk-21`），然后二选一：

- 设 `JAVA_HOME` 指向它，并把 `%JAVA_HOME%\bin` 加进 `PATH`；
- 在 `<GRADLE_USER_HOME>/gradle.properties` 里写 `org.gradle.java.home=D:/jdk-21`
  （推荐，不用动系统环境变量；原因见 README 的「环境依赖」）。

## 2. cmdline-tools（没有 sdkmanager 时）

下载：https://dl.google.com/android/repository/commandlinetools-win-16111833_latest.zip

解压有个容易踩的坑：zip 里的 `cmdline-tools/bin|lib` 必须放进 `cmdline-tools/latest/` 之下，即

```
<SDK>/cmdline-tools/latest/bin/sdkmanager.bat
```

## 3. 模拟器镜像与 AVD

```powershell
sdkmanager --sdk_root=<SDK> "system-images;android-35;google_apis;x86_64"
avdmanager create avd -n Pixel_API35 -k "system-images;android-35;google_apis;x86_64" -d pixel_7
emulator -avd Pixel_API35
```

`default;x86_64` 更轻（不带 Google API）；也可以用 Android Studio 的 Device Manager 建一个
API 35 的 Pixel 7。没有模拟器时用真机也行，`adb devices` 能看到即可。

## 4. 环境变量示例

```
ANDROID_HOME      = D:\AndroidSDK
ANDROID_SDK_ROOT  = D:\AndroidSDK
JAVA_HOME         = D:\jdk-21
PATH             += %JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%ANDROID_HOME%\cmdline-tools\latest\bin
```

## 5. 依赖仓库可达性

国内网络下部分官方源不可直连（实测结果）：

| 仓库 | 状态 |
|---|---|
| `maven.google.com` | **超时** |
| `dl.google.com/dl/android/maven2` | 200（同一份内容，可替代） |
| `repo1.maven.org` | 200 |
| `maven.aliyun.com/repository/google` | 200（可用镜像） |
| `maven.aliyun.com/repository/public` | 200（可用镜像） |
| `services.gradle.org` | **超时** |
| `mirrors.cloud.tencent.com/gradle` | 200（Gradle 发行包镜像） |

项目里已经按这个结论配好了：`settings.gradle.kts` 用阿里云镜像优先、官方仓库兜底，
wrapper 的 `distributionUrl` 指向腾讯云镜像，`gradle.properties` 里设置了 HTTP 超时。