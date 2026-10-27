---
status: accepted
---

# 非模块化 classpath 构建

项目最初是 JPMS 模块化（module-info.java + requires/opens），commit `3b8d8c5` 切换为 classpath 非模块化构建并删除 module-info。理由：JavaFX/ControlsFX/Tika 等依赖图的 requires/opens 维护成本高于单模块桌面应用从强封装获得的收益。历史上 CLAUDE.md 曾以 JPMS 为架构约束，已同步修正——不要按旧约束给新依赖补 requires/opens，也不要把 module-info 加回来。
