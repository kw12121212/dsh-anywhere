import Foundation

/// live work 条目到工作短语（与 `WorkPhrase.kt` 对齐）。
public enum WorkPhrase: Sendable {
    private static let ownMax = 160
    private static let targetMax = 80

    private enum Phrase: Sendable {
        case thinking, onIt, switchFolder, freshChat, waitAnswer, askBot, stopBot
        case createBot, updateBot, team, readGroup, readOwn, remember, forget, recall
        case keys, createGroup, updateGroup, deleteGroup, postGroup, computer
        case searchWeb, readPage, shell, openFile, findFiles, write, image

        func text(_ lang: I18n.Lang) -> String {
            switch (self, lang) {
            case (.thinking, .zh): return "思考中"
            case (.thinking, .en): return "Thinking about it"
            case (.onIt, .zh): return "处理中"
            case (.onIt, .en): return "On it"
            case (.switchFolder, .zh): return "切换工作文件夹"
            case (.switchFolder, .en): return "Switching working folder"
            case (.freshChat, .zh): return "转入新对话"
            case (.freshChat, .en): return "Moving to a fresh conversation"
            case (.waitAnswer, .zh): return "等你回答"
            case (.waitAnswer, .en): return "Waiting for your answer"
            case (.askBot, .zh): return "询问其他 Bot"
            case (.askBot, .en): return "Asking another Bot"
            case (.stopBot, .zh): return "停止其他 Bot"
            case (.stopBot, .en): return "Stopping another Bot"
            case (.createBot, .zh): return "创建 Bot"
            case (.createBot, .en): return "Creating a Bot"
            case (.updateBot, .zh): return "更新 Bot"
            case (.updateBot, .en): return "Updating a Bot"
            case (.team, .zh): return "查看团队"
            case (.team, .en): return "Checking the team"
            case (.readGroup, .zh): return "阅读群聊"
            case (.readGroup, .en): return "Reading a group chat"
            case (.readOwn, .zh): return "回看聊天"
            case (.readOwn, .en): return "Looking back through the chat"
            case (.remember, .zh): return "写入记忆"
            case (.remember, .en): return "Saving to memory"
            case (.forget, .zh): return "更新记忆"
            case (.forget, .en): return "Updating memory"
            case (.recall, .zh): return "查看记忆"
            case (.recall, .en): return "Checking memory"
            case (.keys, .zh): return "查看密钥"
            case (.keys, .en): return "Checking the keys"
            case (.createGroup, .zh): return "创建群聊"
            case (.createGroup, .en): return "Creating a group"
            case (.updateGroup, .zh): return "更新群聊"
            case (.updateGroup, .en): return "Updating the group"
            case (.deleteGroup, .zh): return "删除群聊"
            case (.deleteGroup, .en): return "Deleting the group"
            case (.postGroup, .zh): return "在群里发言"
            case (.postGroup, .en): return "Posting in the group"
            case (.computer, .zh): return "使用电脑"
            case (.computer, .en): return "Using the computer"
            case (.searchWeb, .zh): return "联网搜索"
            case (.searchWeb, .en): return "Searching online"
            case (.readPage, .zh): return "阅读网页"
            case (.readPage, .en): return "Reading a page"
            case (.shell, .zh): return "运行命令"
            case (.shell, .en): return "Running shell commands"
            case (.openFile, .zh): return "打开文件"
            case (.openFile, .en): return "Opening a file"
            case (.findFiles, .zh): return "查找文件"
            case (.findFiles, .en): return "Looking through files"
            case (.write, .zh): return "写入修改"
            case (.write, .en): return "Writing changes"
            case (.image, .zh): return "处理图片"
            case (.image, .en): return "Working on an image"
            }
        }
    }

    private static let exact: [String: Phrase] = [
        "ask_user_question": .waitAnswer,
        "list_secrets": .keys,
        "message_bot": .askBot,
        "stop_bot": .stopBot,
        "create_bot": .createBot,
        "update_bot": .updateBot,
        "list_bots": .team,
        "read_group_chat": .readGroup,
        "read_own_chat": .readOwn,
        "bot_remember": .remember,
        "remember": .remember,
        "bot_forget": .forget,
        "forget": .forget,
        "bot_recall": .recall,
        "recall": .recall,
        "create_group": .createGroup,
        "update_group": .updateGroup,
        "delete_group": .deleteGroup,
        "post_to_group": .postGroup,
    ]

    private static let generic: Set<String> = ["ask_user", "request_secret", "group_relay"]

    private static let patterns: [(regex: NSRegularExpression, phrase: Phrase)] = [
        (try! NSRegularExpression(pattern: #"^cua(_driver_native__|$)|^computer_"#), .computer),
        (try! NSRegularExpression(pattern: #"web_?search|search_?web"#, options: .caseInsensitive), .searchWeb),
        (try! NSRegularExpression(pattern: #"fetch|browse|browser|web"#, options: .caseInsensitive), .readPage),
        (try! NSRegularExpression(pattern: #"shell|bash|pwsh|powershell|exec|command|terminal"#, options: .caseInsensitive), .shell),
        (try! NSRegularExpression(pattern: #"^(read|view|cat)\b|read_"#, options: .caseInsensitive), .openFile),
        (try! NSRegularExpression(pattern: #"grep|glob|list_dir|^ls$|find|search"#, options: .caseInsensitive), .findFiles),
        (try! NSRegularExpression(pattern: #"write|edit|patch|apply|replace"#, options: .caseInsensitive), .write),
        (try! NSRegularExpression(pattern: #"image|photo|draw"#, options: .caseInsensitive), .image),
    ]

    private static let trailingRegex = try! NSRegularExpression(pattern: #"[\s.。…]+$"#)

    public static func toolLabel(_ tool: String, lang: I18n.Lang = I18n.lang) -> String? {
        if generic.contains(tool) { return nil }
        if let hit = exact[tool] {
            return hit.text(lang)
        }
        let ns = tool as NSString
        let range = NSRange(location: 0, length: ns.length)
        for entry in patterns where entry.regex.firstMatch(in: tool, range: range) != nil {
            return entry.phrase.text(lang)
        }
        return nil
    }

    public static func phrase(_ doing: JsonObject?, lang: I18n.Lang = I18n.lang) -> String {
        guard let doing else { return Phrase.thinking.text(lang) }
        if doing.bool("switching") == true {
            let folder = doing.bool("folder") == true
            return (folder ? Phrase.switchFolder : Phrase.freshChat).text(lang)
        }
        if let said = doing.nonBlankString("said") ?? doing.nonBlankString("note") {
            let ns = said as NSString
            let stripped = trailingRegex.stringByReplacingMatches(
                in: said,
                range: NSRange(location: 0, length: ns.length),
                withTemplate: ""
            ).trimmingCharacters(in: .whitespacesAndNewlines)
            if !stripped.isEmpty {
                return I18n.clip(stripped, maxUTF16: ownMax)
            }
        }
        guard let tool = doing.nonBlankString("tool") else {
            return Phrase.thinking.text(lang)
        }
        let label = toolLabel(tool, lang: lang) ?? Phrase.onIt.text(lang)
        if let target = doing.nonBlankString("target")?.trimmingCharacters(in: .whitespacesAndNewlines), !target.isEmpty {
            return "\(label) · \(I18n.clip(target, maxUTF16: targetMax))"
        }
        return label
    }
}
