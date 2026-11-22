# libysmboot.so（YSM GLIBC_2.18 兼容层）

这里保存打进 `src/main/resources/native/libysmboot.so` 的同一份源码，便于审计与重建。

- `libysmboot.c`：在 constructor 中给 `libc.so.6` 的 verdef 链补 `GLIBC_2.18`，
  并实现 `__cxa_thread_atexit_impl@GLIBC_2.18`。
- `boot.map`：只导出 `__cxa_thread_atexit_impl`，其余符号本地化。

当前内置二进制：

```
size   15592
sha256 0f9f12aa4df7d24e4a7531390cb5134309affd99342110c260a515ba1583c73f
```

它只依赖 `GLIBC_2.2.5`（`libc.so.6` / `libpthread.so.0`），可在 CentOS 7 等 glibc 2.17
系统上加载。重建时需要在 glibc 2.17 的构建环境（或对应的 sysroot）中编译，否则生成的
`.so` 可能反过来要求更新的 GLIBC。x86_64 以外的架构需要单独交叉编译。

运行条件与开关见项目 README 的 “YSM / GLIBC_2.18 compatibility” 一节。
