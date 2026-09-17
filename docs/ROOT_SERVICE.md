# Root 服务自启指南

## 问题

OpenClaw 在 Termux 中运行时，root 守护进程无法在 Termux 启动时自动运行。

## 解决方案

### 方法 1：手动启动（每次重启后）

```bash
su -c 'setsid /data/data/app.botdrop/files/home/root_daemon.sh &'
```

### 方法 2：Termux 自启脚本

1. 创建自启脚本：
```bash
mkdir -p ~/.termux/boot
cat > ~/.termux/boot/root_daemon.sh << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
sleep 5 && su -c '/data/data/app.botdrop/files/home/root_daemon.sh &'
SCRIPT
chmod 755 ~/.termux/boot/root_daemon.sh
```

2. 启用 Termux 自启：
   - 打开 Termux
   - 执行 `termux-setup-storage`（如果还没执行）
   - 重启 Termux App

### 方法 3：一键部署命令

```bash
su -c 'pkill -9 -f root_daemon 2>/dev/null; echo "#!/system/bin/sh\nexec >/dev/null 2>&1\nCMD_DIR=\"/data/data/app.botdrop/files/home/.root_cmds\"\nOUT_DIR=\"/data/data/app.botdrop/files/home/.root_outputs\"\nmkdir -p \"\$CMD_DIR\" \"\$OUT_DIR\"\nwhile true; do for f in \"\$CMD_DIR\"/*.cmd; do [ -f \"\$f\" ] || continue; id=\$(basename \"\$f\" .cmd); output=\$(su -c \"cat \\\"\$f\\\" | /system/bin/sh\" 2>&1); exit_code=\$?; echo \"\$output\" | base64 > \"\$OUT_DIR/\${id}.out\"; echo \"exit_code=\$exit_code\" > \"\$OUT_DIR/\${id}.status\"; rm -f \"\$f\"; done; sleep 1; done" > /data/data/app.botdrop/files/home/root_daemon.sh && chmod 755 /data/data/app.botdrop/files/home/root_daemon.sh && setsid /data/data/app.botdrop/files/home/root_daemon.sh &'
```

## 验证服务运行

```bash
ps -A | grep root_daemon
echo 'whoami' > /data/data/app.botdrop/files/home/.root_cmds/test.cmd
sleep 2
cat /data/data/app.botdrop/files/home/.root_outputs/test.out | base64 -d
# 应输出：root
```
