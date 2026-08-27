// Shizuku UserService AIDL：以 shell 身份执行命令并返回 stdout
package com.nya.app.shizuku;

interface INyaShellService {
    /** 执行 shell 命令（sh -c command），返回 stdout + stderr 合并文本 */
    String execCommand(String command);
}
