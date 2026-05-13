compose 资源文件夹, 会跟随打包, 可使用 System.getProperty("compose.application.resources.dir") 访问.

See <https://github.com/JetBrains/compose-multiplatform/blob/master/tutorials/Native_distributions_and_local_execution/README.md#packaging-resources>

### Versions

#### Libvlc

- macos-arm64: 3.0.20
- macos-x64: 3.0.18
- windows-x64: 3.0.20
- windows-arm64: 3.0.23, downloaded by `ci-helper/vlc-woa64/prepare-vlc-windows-arm64.ps1`

Windows ARM64 intentionally uses VLC 3.0.23 because VideoLAN does not publish a
3.0.20 `winarm64` package. Keep the x64 vendored runtime unchanged unless a
separate VLC refresh is being reviewed.
