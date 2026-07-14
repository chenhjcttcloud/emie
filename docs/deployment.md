# EMIE 部署信息

生产发布的完整检查、推送、迁移、验证和回滚流程见 [`release-runbook.md`](release-runbook.md)，每次实际操作必须追加到 [`release-records.md`](release-records.md)。本文件只维护相对稳定的环境与服务信息，不记录任何真实密码。

## 阿里云生产服务器

| 配置项 | 值 |
|---|---|
| 地址 | `47.119.179.230` |
| SSH 用户 | `root` |
| SSH 端口 | `22` |
| 用途 | EMIE 生产环境服务器 |

服务器密码保存在项目根目录的 `.server.local.env`，该文件已加入 `.gitignore`，不会提交到版本库。

```dotenv
SERVER_HOST=47.119.179.230
SERVER_USER=root
SERVER_PORT=22
SERVER_PASSWORD=<本机忽略文件中的实际密码>
```

## PDF / PPT 在线预览

`docker-compose.yml` 中的 `preview-converter` 服务负责将 PPT/PPTX 转换为 PDF。端口只绑定到服务器回环地址 `127.0.0.1:3000`，不对公网开放；主应用通过 `APP_PREVIEW_CONVERTER_URL` 调用。

预览相关配置：

| 环境变量 | 默认值 | 说明 |
|---|---:|---|
| `APP_PREVIEW_MAX_SOURCE_BYTES` | `52428800` | 可转换演示文稿最大 50MB |
| `APP_PREVIEW_MAX_CACHE_BYTES` | `2147483648` | 预览 PDF 缓存最大 2GB |
| `APP_PREVIEW_TIMEOUT_SECONDS` | `120` | 单次转换超时时间 |

PDF 原文件直接预览；PPT/PPTX 首次访问时异步转换，结果缓存在 `uploads/preview-cache/`。部署或升级时需同时启动应用和转换服务：

```bash
docker compose up -d --build
```
