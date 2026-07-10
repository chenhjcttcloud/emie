# EMIE 部署信息

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
