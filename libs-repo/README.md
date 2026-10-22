# libs-repo

Shizuku（M4 特权通道）的本地 Maven 仓库镜像，**不是**本工程自己的代码。

- 来源：Maven Central，`dev.rikka.shizuku:{api,provider,aidl,shared}:13.1.5`（MIT License，作者 Rikka）
- 为什么放在工程里：本机构建环境离线（`dl.google.com` / `maven.google.com` 不可达，去掉
  `--offline` 会因为要校验 google 仓库的构件而失败），而 Maven Central 可达。把这四个构件
  固化进来，`--offline` 就能正常解析。
- 合计约 60KB，只有一处依赖用到：`app/build.gradle` 里的 `implementation libs.shizuku.api` /
  `libs.shizuku.provider`，以及 `settings.gradle` 里第一顺位的
  `maven { url = uri("libs-repo") }`。
- **代理长期可用后可以直接删除**：删掉本目录与 `settings.gradle` 里那个 `maven {}` 块即可，
  Gradle 会回到从 `mavenCentral()` 正常解析。

不要把这里的文件当成需要维护的源码；升级 Shizuku 版本时直接从 Maven Central 取新的
`<artifact>-<version>.{aar,pom}` 放进来（同时改 `gradle/libs.versions.toml` 里的 `shizuku`）。
