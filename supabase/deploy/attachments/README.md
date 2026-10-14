# MCSP 附件发送修复部署包

目标项目：`mbtgwmtgxlufyfcrzwto`。对应分支：`integrate/context-engine-sensing-main`，基线提交 `086240d`。

2026-10-14 的只读诊断确认，手机连接的线上数据库缺少 `public.shared_files.allow_downloads`。此版本会为每个相机照片、相册图片、视频和文件写入下载许可，所以缺少这个字段会导致各类附件共同发送失败。相机生成的照片仍保存在手机上，失败队列可在修复后重试。

本包需要项目管理员执行。没有在生产环境执行过。SQL 不删除现有群、消息或附件记录；可重复执行，失败会回滚。它要求项目已有基础的群组、成员、用户资料和 shared_files 表。

## 1. 修复数据库

在目标项目的 Supabase Dashboard → SQL Editor 中，用数据库所有者权限运行 `01-repair-attachments.sql`。

脚本补齐附件下载许可、私有 `group-files` 存储桶、上传策略、文件列表和预览／下载授权 RPC。保留 20 MiB 单附件上限；只有群成员可上传到自己的目录，下载关闭时其他成员只能预览。旧附件的下载许可默认为开启。若项目尚无群昵称字段，列表使用用户显示名。

运行 `02-verify-attachments.sql`，8 项结果必须全部为 `true`。这只验证数据库；还需要执行下一步部署服务。

## 2. 部署预览和下载服务

在解压后的目录（含 `supabase/` 文件夹），或仓库根目录运行：

```sh
npx supabase@2.117.0 login
npx supabase@2.117.0 functions deploy group-file-url --project-ref mbtgwmtgxlufyfcrzwto
```

包内的 `supabase/config.toml` 配置 `verify_jwt = false`，因为该函数会自行调用 Supabase Auth 验证登录令牌，再以调用者身份检查群成员和下载许可。不能删除函数内的身份验证。签名链接使用运行环境提供的服务凭据，管理员不需要把 service-role key 发给开发者或写入 Android 应用。

如果使用 Dashboard 部署：新建／更新名为 `group-file-url` 的 Edge Function，复制包内 `supabase/functions/group-file-url/index.ts`，并按上述说明关闭网关的旧 JWT 检查。函数自身的 Auth 验证和权限检查必须保留。

## 3. 验收

在仅包含测试账号的群中，分别发送相机照片、相册图片、文档和短视频。确认上传完成、群聊及群文件列表显示附件，图片／视频可预览，允许下载的文件可保存。

再用两个测试成员验证：发送时关闭“Allow others to download”，另一成员可以预览但不能下载；上传者仍可下载自己的附件；群外账号不能访问。大于 20 MiB 的媒体应提示大小超限。文档选择界面当前另有 10 MiB 的选取上限。

最后回到原手机，在之前失败的附件卡片上点击 Retry。若操作系统已经清理源文件，则重新选取并发送。仅重复安装 APK 或增加相册权限不能修复缺少的服务端字段。

## 迁移记录

这是针对部署缺口的独立修复脚本，来源是仓库的 `20260925070000_group_files.sql` 和 `20261011170000_attachment_download_permissions.sql`。它不修改 `supabase_migrations` 历史。

如果项目一直使用 CLI 管理迁移，优先检查 `npx supabase@2.117.0 migration list --linked` 和 `npx supabase@2.117.0 db push --dry-run`，按项目正常流程应用缺失迁移。若使用本包手动修复，管理员应核对已有结构与这两个迁移的全部效果后再协调迁移记录；不要直接重跑非幂等旧迁移，也不要重置数据库。

## 客户端改动

配套 Android 补丁将已识别的附件服务部署缺失错误显示为“Attachment sharing is not available on the server yet. Contact the app administrator, then retry.”，保留失败卡片的 Retry 操作。不会把未获授权的附件降级为公开链接，也不会忽略用户的下载许可。

数据库和函数部署完成后，原集成版客户端即可继续发送；配套 APK 的作用是改善错误提示。

## 已完成的本地验证（2026-10-14）

- 777 个 JVM 测试通过，`assembleDebug` 构建成功。
- 部署 SQL 在已有结构、缺失下载许可字段、重复执行三种情形下，共通过 90 项数据库断言。
- 模拟同时缺少下载许可和群昵称字段，8 项部署检查全部通过。
- 本地 Supabase 中使用三个临时测试账号，JPEG、PNG、TXT、MP4 均完成上传、元数据保存、内容一致的成员预览与上传者下载；关闭下载时其他成员的下载、群外访问均被拒绝。测试数据已清理。
- 线上只读检查已确认缺失字段。由于没有项目管理权限，未执行线上迁移或部署；生产验收仍需管理员完成。
