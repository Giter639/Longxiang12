// 专注有度 · 骨架工程（X02 客户端架构与页面框架）
// CI 环境（GitHub Actions 自动注入 CI=true）直连官方源——runner 访问阿里云会超时导致解析失败；
// 本地（国内网络）保持阿里云镜像优先，官方源兜底。
val isCi = System.getenv("CI") != null

pluginManagement {
    repositories {
        if (isCi) {
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (isCi) {
            google()
            mavenCentral()
        } else {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "FocusDu"
include(":app")
