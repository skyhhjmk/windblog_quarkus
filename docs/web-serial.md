# Web Serial 串口终端

工具箱中的 `/tools/serial` 是一个**纯前端**串口调试工具：页面通过浏览器的
[Web Serial API](https://developer.mozilla.org/docs/Web/API/Web_Serial_API) 直接访问
用户选择的 USB/串口设备，不经过 WindBlog 服务器。

## 功能

- 波特率：9600 / 19200 / 38400 / 57600 / 115200 / 230400 / 460800 / 921600 / 1500000
- 数据位：7 / 8
- 校验位：none / even / odd
- 停止位：1 / 2
- 流控：none / hardware (RTS/CTS)
- 回车换行：不追加 / CR / LF / CRLF（仅影响浏览器发往设备的换行字节）
- xterm 终端：窗口自适应、复制选中文本、粘贴、清空
- 设备拔出时自动断开

## 浏览器要求

Web Serial 目前只在 Chromium 系浏览器中可用（Chrome、Edge 等），并且页面必须运行在
**HTTPS 或 localhost** 的安全上下文中（`window.isSecureContext`）。不支持时页面会显示
「当前浏览器不支持 Web Serial」，不会影响其他工具箱功能。

## 隐私与安全

- 串口权限由浏览器逐次询问用户，只有用户在当前页面主动选择设备后才会打开端口。
- 串口字节只在本机浏览器与设备之间流动，WindBlog 不代理、不记录、不上传。
- 页面无需登录；不具备任何服务器端写操作。

## 前端实现

- 模板：`src/main/resources/templates/tools/serial.html`（完整页面）与
  `serial.content.html`（PJAX 片段）。
- 脚本：`tools.js` 末尾的 `Web Serial terminal` IIFE。
- xterm 资源：`assets/vendor/xterm/`（`@xterm/xterm@5.5.0` 与
  `@xterm/addon-fit@0.10.0`），只在串口页面首次进入时动态加载。
- PJAX 与页面卸载生命周期：`page:ready` 初始化，`pjax:start` / `pagehide` / `beforeunload`
  关闭串口、释放读写流锁并销毁终端；当打开或关闭失败时通过 `port.forget()` 强制重置
  Chromium `SerialPort` 内部句柄，避免单次连接失败或异常断开后无法再次打开串口。

## 常见问题

- **出现乱码**：先确认设备波特率（多数为 115200），再检查校验位、数据位与停止位。
- **看不到设备**：确认 USB 转串口驱动已在操作系统中识别；Linux 下需要当前用户对
  `/dev/ttyUSB*` 或 `/dev/ttyACM*` 有读写权限。
- **点击连接没有弹窗**：确认使用 Chrome/Edge 且通过 HTTPS 或 localhost 访问。
