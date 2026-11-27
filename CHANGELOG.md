# Changelog

## [0.3.1](https://github.com/CiQian-04/AI-Meal-Planner/compare/v0.3.0...v0.3.1) (2026-11-27)


### Bug Fixes

* 推理模型空响应兜底为无工具 JSON 调用并修复旧 jar 启动 ([9156b15](https://github.com/CiQian-04/AI-Meal-Planner/commit/9156b1557a3e6b1da3d3229cbd38e1c22538dca9))

## [0.3.0](https://github.com/CiQian-04/AI-Meal-Planner/compare/v0.2.0...v0.3.0) (2026-11-27)


### Features

* 合并 53 道用户菜品 SOP，演示菜扩至 75 道，食材库 152 种 ([e19c25a](https://github.com/CiQian-04/AI-Meal-Planner/commit/e19c25abc86cbce537b9f057b607a632ba497b93))
* 合并用户食材表格，价格库扩至 148 种并统一为 8 分类 ([96bf0b2](https://github.com/CiQian-04/AI-Meal-Planner/commit/96bf0b2b1ad6f9690d5766a7830a127373b7f31a))
* 扩充食材库至 96 种、演示菜至 28 道，修复发版后脚本 jar 版本写死 ([ba9ebff](https://github.com/CiQian-04/AI-Meal-Planner/commit/ba9ebff81d3b3f2409f4e10cd339da6006995a97))
* 菜品洗牌抽取避免重复，修复错误响应体前端读不到的问题 ([c6ce79d](https://github.com/CiQian-04/AI-Meal-Planner/commit/c6ce79d7f31bf93e6c971961d2a563c056bdf17c))
* 默认切换 DeepSeek 国内直连，重写 Windows 双击启动脚本 ([833db51](https://github.com/CiQian-04/AI-Meal-Planner/commit/833db514e2a5bd736102b33f3b6647eea2bc849e))
* 默认模型切换到国内中转站 api.li1023.com ([8e27974](https://github.com/CiQian-04/AI-Meal-Planner/commit/8e27974a28caa32d47858c65f8d23bceaf60babb))


### Bug Fixes

* **ci:** Maven 429 重试并共享 Docker 构建缓存 ([3b10b06](https://github.com/CiQian-04/AI-Meal-Planner/commit/3b10b06860feefce21a18c44f99cd01f153d5a2e))
* **ci:** 固定 mock 洗牌种子保证预算断言稳定 ([4d7717d](https://github.com/CiQian-04/AI-Meal-Planner/commit/4d7717da963b67103aefbf13b93e22a7d8c0c1f4))
* **release:** 修复 jar 打包 glob 展开错误，支持手动补发指定 tag ([f93f799](https://github.com/CiQian-04/AI-Meal-Planner/commit/f93f79981e463b849dca10d521a71e25ccefe9b5))
* **test:** 未知食材测试用例改用确实不存在的名称 ([84f401e](https://github.com/CiQian-04/AI-Meal-Planner/commit/84f401e6f916d52a4da33c3ff9bb8bd64022f29c))
* 推理模型空响应导致 Jackson 解析失败，加大 token 预算并加重试 ([4ac1ed5](https://github.com/CiQian-04/AI-Meal-Planner/commit/4ac1ed5ed9117cb32d4854260259eea3b008bc53))
* 默认模型改为 deepseek-flash，文档补充模型名与余额说明 ([9b60b07](https://github.com/CiQian-04/AI-Meal-Planner/commit/9b60b076001814b5a6dc5bb826d48a382aa39d18))


### Documentation

* 添加 Windows 双击启动脚本 ([bc04e6e](https://github.com/CiQian-04/AI-Meal-Planner/commit/bc04e6eb84e994bc20afa6525aa18488e4738715))

## [0.2.0](https://github.com/CiQian-04/AI-Meal-Planner/compare/v0.1.0...v0.2.0) (2026-11-27)


### Features

* AI 菜谱规划师 —— Spring AI Function Calling + Vue 3 ([2f6b8aa](https://github.com/CiQian-04/AI-Meal-Planner/commit/2f6b8aaaea76620575c5b090b85419ad41aafb1d))


### Bug Fixes

* **ci:** CodeQL strategy 键名改 fail-fast，trivy-action 使用 v 前缀 tag ([e2444e6](https://github.com/CiQian-04/AI-Meal-Planner/commit/e2444e69203cf771e09a394fe2e2ba0663ef72ad))
* **ci:** trivy 版本、release-please extra-file 类型、CodeQL 语言标识、移除文档流中的 OpenAPI 导出 ([a11bddf](https://github.com/CiQian-04/AI-Meal-Planner/commit/a11bddf890ce15a2d2f38a0e2c89126bf48bc035))
* **ci:** 补充 frontend/.trivyignore，trivyignores 指向缺失文件会硬报错 ([3710532](https://github.com/CiQian-04/AI-Meal-Planner/commit/37105327e2e36057f83de147449edad5703722fb))
* **docker:** 镜像构建时升级基础包，修复 brotli CVE-2026-111497 等 critical 漏洞 ([2abc7cd](https://github.com/CiQian-04/AI-Meal-Planner/commit/2abc7cd0c5e9a4a23d4678f356dbfeb3c2da245e))
* **security:** Tomcat 升至 10.1.62，Trivy 豁免 Spring 6.2 线无修复版的 4 个 CVE 并文档注明 ([c0c3499](https://github.com/CiQian-04/AI-Meal-Planner/commit/c0c34991b62bb5aba9374b3aa278c593ed01aaf1))
* 容器健康检查、开发代理、发版版本同步 ([5a18255](https://github.com/CiQian-04/AI-Meal-Planner/commit/5a1825541506e8922c56c2cc788b6979d7b76ecc))
* 工具返回值回填、金额一致性、脚本跨平台化 ([135ab80](https://github.com/CiQian-04/AI-Meal-Planner/commit/135ab80f78ca8858e503c962e0ffa1f70aff451c))


### Documentation

* 替换仓库地址占位符为 CiQian-04/AI-Meal-Planner，镜像名转小写 ([2288e24](https://github.com/CiQian-04/AI-Meal-Planner/commit/2288e24e4769c4824fc6123976ede8e2970c7852))
* 补齐社区规范文件、修复文档站构建命令 ([309af37](https://github.com/CiQian-04/AI-Meal-Planner/commit/309af37e0df9ca67d4fd3e17701884537df4fc6e))

## Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Releases are automated by [Release Please](https://github.com/googleapis/release-please);
this file is generated from Conventional Commits.
