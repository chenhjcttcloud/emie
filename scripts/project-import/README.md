# 项目导入模板生成工具

本目录保存项目导入 Excel 模板的生成脚本：

- `build_templates.mjs`：生成通用历史项目导入模板；
- `build_prod_templates.mjs`：生成包含生产参考数据的导入模板。

生成结果写入项目根目录的 `outputs/project-import-templates/`。`outputs/` 是本地生成目录，不提交到 Git；确认可交付的模板应复制到 `docs/templates/project-import/` 并经过检查。

脚本依赖 Codex 工作区提供的 `@oai/artifact-tool`，该依赖未发布到公共 npm。运行前需通过当前工作区依赖环境提供 `node_modules`，不要把本机绝对路径软链接提交到仓库。

运行示例：

```bash
node scripts/project-import/build_templates.mjs
node scripts/project-import/build_prod_templates.mjs
```

生产版脚本包含模板生成时使用的参考数据。更新这些数据前必须确认来源和适用环境，输出文件中不得包含密码、令牌或其他认证信息。
