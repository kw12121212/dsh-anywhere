# 源码核对样本（非真实响应）

本目录的 JSON 按已安装 DS-Bot 0.3.10 源码核对的字段形态构造，**不是**真实服务响应的脱敏副本
（真实夹具在 `../dsbot/`）。用于真实服务暂时无法产生样本的字段（tech/dsbot-api 的
chat-bars-fields-0310）。取得真实样本后应改为 `../dsbot/` 下的脱敏夹具并删除对应文件。

- `chat-bars-0310.json`：state 应答（view + live 合并）中的 bots[].tasks、bots[].blocked、
  rooms[].goal、computerLive、work。核对位置：ds-bot 0.3.10 `src/host/channel.js`（view/live、
  state 应答 `{...view(), liveKey, ...live}`）、`bot-tasks.js` taskViewOf、`turns.js`
  blockBot/doingOf/workView/roomWorkView、`computer/index.js` computerLive、`goals.js`。
  名称与文本均为合成内容。
